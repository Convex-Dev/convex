package convex.etch;

import static convex.etch.EtchConstants.MAGIC_NUMBER;
import static convex.etch.EtchConstants.POINTER_SIZE;
import static convex.etch.EtchConstants.ROOT_INDEX_SIZE;
import static convex.etch.EtchConstants.V3_CIPHER_AES_256_CTR;
import static convex.etch.EtchConstants.V3_CIPHER_CHACHA20;
import static convex.etch.EtchConstants.V3_CIPHER_NONE;
import static convex.etch.EtchConstants.V3_CIPHER_OFFSET;
import static convex.etch.EtchConstants.V3_CLEAN_CLOSED;
import static convex.etch.EtchConstants.V3_CLOSE_STATE_OFFSET;
import static convex.etch.EtchConstants.V3_FILE_SALT_SIZE;
import static convex.etch.EtchConstants.V3_FILE_SALT_OFFSET;
import static convex.etch.EtchConstants.V3_GENERATION_OFFSET;
import static convex.etch.EtchConstants.V3_HEADER_A_OFFSET;
import static convex.etch.EtchConstants.V3_HEADER_B_OFFSET;
import static convex.etch.EtchConstants.V3_HEADER_CHECK_OFFSET;
import static convex.etch.EtchConstants.V3_HEADER_CHECK_SIZE;
import static convex.etch.EtchConstants.V3_HEADER_COPY_SIZE;
import static convex.etch.EtchConstants.V3_HEADER_PREFIX_SIZE;
import static convex.etch.EtchConstants.V3_HEADER_REGION_SIZE;
import static convex.etch.EtchConstants.V3_INDEX_ENCRYPTED;
import static convex.etch.EtchConstants.V3_INDEX_ENCRYPTION_OFFSET;
import static convex.etch.EtchConstants.V3_INDEX_PLAINTEXT;
import static convex.etch.EtchConstants.V3_INDEX_START;
import static convex.etch.EtchConstants.V3_INDEX_START_OFFSET;
import static convex.etch.EtchConstants.V3_OPEN;
import static convex.etch.EtchConstants.V3_PUBLIC_KEY_HINT_OFFSET;
import static convex.etch.EtchConstants.V3_PUBLIC_KEY_HINT_SIZE;
import static convex.etch.EtchConstants.V3_ROOT_HASH_OFFSET;
import static convex.etch.EtchConstants.V3_SYNCED_FILE_END_OFFSET;
import static convex.etch.EtchConstants.VERSION_3;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Objects;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import convex.core.data.AccountKey;
import convex.core.data.Hash;
import convex.core.util.Utils;

/**
 * Etch v3 dual-copy header state and exact byte codec.
 *
 * <p>This component is deliberately off the record and index hot paths. Clean
 * reopening is supported by normal Etch access; dirty reopening remains
 * fail-fast until the separate recovery scanner is complete.</p>
 */
final class EtchV3Header extends AEtchHeader {
	private static final String SHA_256="SHA-256";
	private static final String HMAC_SHA_256="HmacSHA256";
	private static final long UNSIGNED_MAX_GENERATION=-1L;
	private static final SecureRandom SECURE_RANDOM=new SecureRandom();

	private final int cipherId;
	private final boolean encryptedIndex;
	private final byte[] fileSalt;
	private final AccountKey publicKeyHint;
	private final byte[] headerMacKey;
	private final boolean degraded;

	private long generation;
	private long syncedFileEnd;
	private Hash rootHash;
	private long closeState;
	private int activeCopy;

	private EtchV3Header(int cipherId, boolean encryptedIndex, long generation,
			long syncedFileEnd, long indexStart, Hash rootHash, byte[] fileSalt,
			AccountKey publicKeyHint, long closeState, int activeCopy, boolean degraded,
			byte[] headerMacKey) {
		super(VERSION_3,indexStart,syncedFileEnd);
		this.cipherId=cipherId;
		this.encryptedIndex=encryptedIndex;
		this.generation=generation;
		this.syncedFileEnd=syncedFileEnd;
		this.rootHash=Objects.requireNonNull(rootHash,"rootHash");
		this.fileSalt=fileSalt.clone();
		this.publicKeyHint=normalisePublicKeyHint(publicKeyHint);
		this.closeState=closeState;
		this.activeCopy=activeCopy;
		this.degraded=degraded;
		this.headerMacKey=headerMacKey;
	}

	static EtchV3Header create(int cipherId, boolean encryptedIndex, byte[] fileSalt,
			byte[] masterKey) {
		return create(cipherId,encryptedIndex,fileSalt,null,masterKey);
	}

	static EtchV3Header create(EtchConfig config, byte[] masterKey) {
		byte[] fileSalt=new byte[V3_FILE_SALT_SIZE];
		SECURE_RANDOM.nextBytes(fileSalt);
		return create(config.getCipherMode().fileId(),config.isIndexEncrypted(),
				fileSalt,config.getPublicKeyHint(),masterKey);
	}

	static byte[] resolveKey(byte[] copyA, byte[] copyB, EtchConfig config,
			String fileName) throws IOException {
		Validation a=decodeCopy(copyA,0);
		Validation b=decodeCopy(copyB,1);
		Candidate selected;
		if ((a.candidate==null)&&(b.candidate==null)) {
			IOException failure=new IOException("No structurally valid Etch v3 header copy: "+fileName);
			failure.addSuppressed(a.failure);
			failure.addSuppressed(b.failure);
			throw failure;
		} else if (a.candidate==null) {
			selected=b.candidate;
		} else if (b.candidate==null) {
			selected=a.candidate;
		} else {
			if (!a.candidate.sameImmutableFields(b.candidate)) {
				throw new IOException("Etch v3 headers disagree on immutable fields: "+fileName);
			}
			selected=a.candidate;
		}
		if (selected.cipherId==V3_CIPHER_NONE) return null;
		if (config==null) throw new IOException("Encrypted Etch requires configured key resolution: "+fileName);
		return config.resolveKey(selected.publicKeyHint);
	}

	static EtchV3Header create(int cipherId, boolean encryptedIndex, byte[] fileSalt,
			AccountKey publicKeyHint, byte[] masterKey) {
		publicKeyHint=normalisePublicKeyHint(publicKeyHint);
		validateImmutable(cipherId,encryptedIndex,V3_INDEX_START,fileSalt,publicKeyHint);
		byte[] macKey=deriveMacKey(cipherId,masterKey,fileSalt);
		return new EtchV3Header(cipherId,encryptedIndex,0L,0L,V3_INDEX_START,
				Hash.UNSET_HASH,fileSalt,publicKeyHint,V3_OPEN,-1,false,macKey);
	}

	static EtchV3Header select(byte[] copyA, byte[] copyB, byte[] masterKey,
			String fileName) throws IOException {
		Validation a=validateCopy(copyA,masterKey,0);
		Validation b=validateCopy(copyB,masterKey,1);
		Candidate selected;
		boolean degraded;
		if ((a.candidate==null)&&(b.candidate==null)) {
			IOException failure=new IOException("No valid Etch v3 header copy: "+fileName);
			failure.addSuppressed(a.failure);
			failure.addSuppressed(b.failure);
			throw failure;
		} else if (a.candidate==null) {
			selected=b.candidate;
			degraded=true;
		} else if (b.candidate==null) {
			selected=a.candidate;
			degraded=true;
		} else {
			Candidate first=a.candidate;
			Candidate second=b.candidate;
			if (!first.sameImmutableFields(second)) {
				throw new IOException("Valid Etch v3 headers disagree on immutable fields: "+fileName);
			}
			int comparison=Long.compareUnsigned(first.generation,second.generation);
			if (comparison==0) {
				if (!Arrays.equals(copyA,copyB)) {
					throw new IOException("Ambiguous equal-generation Etch v3 headers: "+fileName);
				}
				selected=second;
			} else {
				selected=(comparison>0)?first:second;
			}
			degraded=false;
		}

		byte[] macKey=deriveMacKey(selected.cipherId,masterKey,selected.fileSalt);
		return selected.toHeader(degraded,macKey);
	}

	private static Validation validateCopy(byte[] copy, byte[] masterKey, int copyIndex) {
		try {
			return new Validation(decodeAndVerify(copy,masterKey,copyIndex),null);
		} catch (IOException | RuntimeException e) {
			IOException failure=(e instanceof IOException io)?io
					:new IOException("Invalid Etch v3 header copy "+copyIndex,e);
			return new Validation(null,failure);
		}
	}

	private static Validation decodeCopy(byte[] copy, int copyIndex) {
		try {
			return new Validation(decode(copy,copyIndex),null);
		} catch (IOException | RuntimeException e) {
			IOException failure=(e instanceof IOException io)?io
					:new IOException("Invalid Etch v3 header copy "+copyIndex,e);
			return new Validation(null,failure);
		}
	}

	private static Candidate decodeAndVerify(byte[] copy, byte[] masterKey, int copyIndex)
			throws IOException {
		Candidate candidate=decode(copy,copyIndex);
		byte[] macKey=deriveMacKey(candidate.cipherId,masterKey,candidate.fileSalt);
		try {
			byte[] expected=calculateCheck(copy,candidate.cipherId,macKey);
			byte[] actual=Arrays.copyOfRange(copy,V3_HEADER_CHECK_OFFSET,
					V3_HEADER_CHECK_OFFSET+V3_HEADER_CHECK_SIZE);
			if (!MessageDigest.isEqual(expected,actual)) {
				throw new IOException("Etch v3 header check failed for copy "+copyIndex);
			}
		} finally {
			if (macKey!=null) Arrays.fill(macKey,(byte)0);
		}
		return candidate;
	}

	private static Candidate decode(byte[] copy, int copyIndex) throws IOException {
		if ((copy==null)||(copy.length!=V3_HEADER_COPY_SIZE)) {
			throw new IOException("Etch v3 header copy must be exactly "+V3_HEADER_COPY_SIZE+" bytes");
		}
		int magic=Utils.readShort(copy,0)&0xffff;
		short version=Utils.readShort(copy,Short.BYTES);
		if ((magic!=MAGIC_NUMBER)||(version!=VERSION_3)) {
			throw new IOException("Invalid Etch v3 format probe in header copy "+copyIndex);
		}

		int cipherId=Utils.readShort(copy,V3_CIPHER_OFFSET)&0xffff;
		int indexMode=Utils.readShort(copy,V3_INDEX_ENCRYPTION_OFFSET)&0xffff;
		if ((indexMode!=V3_INDEX_PLAINTEXT)&&(indexMode!=V3_INDEX_ENCRYPTED)) {
			throw new IOException("Invalid Etch v3 index encryption value: "+indexMode);
		}
		boolean encryptedIndex=indexMode==V3_INDEX_ENCRYPTED;
		long generation=Utils.readLong(copy,V3_GENERATION_OFFSET,Long.BYTES);
		long syncedFileEnd=Utils.readLong(copy,V3_SYNCED_FILE_END_OFFSET,Long.BYTES);
		long indexStart=Utils.readLong(copy,V3_INDEX_START_OFFSET,Long.BYTES);
		byte[] rootBytes=Arrays.copyOfRange(copy,V3_ROOT_HASH_OFFSET,V3_ROOT_HASH_OFFSET+Hash.LENGTH);
		Hash rootHash=Arrays.equals(rootBytes,Utils.ZERO_BYTES_32)
				?Hash.UNSET_HASH:Hash.wrap(rootBytes);
		byte[] fileSalt=Arrays.copyOfRange(copy,V3_FILE_SALT_OFFSET,
				V3_FILE_SALT_OFFSET+V3_FILE_SALT_SIZE);
		byte[] hintBytes=Arrays.copyOfRange(copy,V3_PUBLIC_KEY_HINT_OFFSET,
				V3_PUBLIC_KEY_HINT_OFFSET+V3_PUBLIC_KEY_HINT_SIZE);
		AccountKey publicKeyHint=Arrays.equals(hintBytes,Utils.ZERO_BYTES_32)
				?null:AccountKey.wrap(hintBytes);
		long closeState=Utils.readLong(copy,V3_CLOSE_STATE_OFFSET,Long.BYTES);

		validateImmutable(cipherId,encryptedIndex,indexStart,fileSalt,publicKeyHint);
		validateMutable(syncedFileEnd,indexStart,closeState);
		for (int i=V3_HEADER_PREFIX_SIZE;i<V3_HEADER_CHECK_OFFSET;i++) {
			if (copy[i]!=0) throw new IOException("Non-zero reserved Etch v3 header byte at "+i);
		}

		return new Candidate(cipherId,encryptedIndex,generation,syncedFileEnd,
				indexStart,rootHash,fileSalt,publicKeyHint,closeState,copyIndex);
	}

	private static void validateImmutable(int cipherId, boolean encryptedIndex,
			long indexStart, byte[] fileSalt, AccountKey publicKeyHint) {
		if ((cipherId!=V3_CIPHER_NONE)&&(cipherId!=V3_CIPHER_AES_256_CTR)
				&&(cipherId!=V3_CIPHER_CHACHA20)) {
			throw new IllegalArgumentException("Unsupported Etch v3 cipher ID: "+cipherId);
		}
		if (encryptedIndex&&(cipherId==V3_CIPHER_NONE)) {
			throw new IllegalArgumentException("Etch v3 index encryption requires a file cipher");
		}
		if (indexStart!=V3_INDEX_START) {
			throw new IllegalArgumentException("Invalid Etch v3 index start: "+indexStart);
		}
		if ((fileSalt==null)||(fileSalt.length!=V3_FILE_SALT_SIZE)) {
			throw new IllegalArgumentException("Invalid Etch v3 file salt length");
		}
		int combined=0;
		for (byte b:fileSalt) combined|=b;
		if (combined==0) throw new IllegalArgumentException("Etch v3 file salt must not be all zero");
		if ((publicKeyHint!=null)&&(publicKeyHint.count()!=V3_PUBLIC_KEY_HINT_SIZE)) {
			throw new IllegalArgumentException("Invalid Etch v3 public-key hint length");
		}
	}

	private static AccountKey normalisePublicKeyHint(AccountKey publicKeyHint) {
		return ((publicKeyHint==null)||AccountKey.ZERO.equals(publicKeyHint))?null:publicKeyHint;
	}

	private static void validateMutable(long syncedFileEnd, long indexStart, long closeState) {
		long minimumEnd;
		try {
			minimumEnd=Math.addExact(indexStart,(long)ROOT_INDEX_SIZE*POINTER_SIZE);
		} catch (ArithmeticException e) {
			throw new IllegalArgumentException("Overflowing Etch v3 index extent",e);
		}
		if (syncedFileEnd<minimumEnd) {
			throw new IllegalArgumentException("Etch v3 synced file end precedes the root index end");
		}
		if ((closeState!=V3_OPEN)&&(closeState!=V3_CLEAN_CLOSED)) {
			throw new IllegalArgumentException("Invalid Etch v3 close state: "+closeState);
		}
	}

	private static byte[] deriveMacKey(int cipherId, byte[] masterKey, byte[] fileSalt) {
		if (cipherId==V3_CIPHER_NONE) return null;
		if ((masterKey==null)||(masterKey.length!=EtchConstants.V3_MASTER_KEY_SIZE)) {
			throw new IllegalArgumentException("Encrypted Etch v3 header requires a 32-byte master key");
		}
		return EtchKeyDerivation.deriveHeaderMacKey(masterKey,fileSalt);
	}

	byte[] encode(long generation, long syncedFileEnd, Hash rootHash, long closeState) {
		validateMutable(syncedFileEnd,indexStart(),closeState);
		byte[] copy=new byte[V3_HEADER_COPY_SIZE];
		Utils.writeShort(copy,0,(short)MAGIC_NUMBER);
		Utils.writeShort(copy,Short.BYTES,VERSION_3);
		Utils.writeShort(copy,V3_CIPHER_OFFSET,(short)cipherId);
		Utils.writeShort(copy,V3_INDEX_ENCRYPTION_OFFSET,
				(short)(encryptedIndex?V3_INDEX_ENCRYPTED:V3_INDEX_PLAINTEXT));
		Utils.writeLong(copy,V3_GENERATION_OFFSET,generation);
		Utils.writeLong(copy,V3_SYNCED_FILE_END_OFFSET,syncedFileEnd);
		Utils.writeLong(copy,V3_INDEX_START_OFFSET,indexStart());
		if (!Hash.UNSET_HASH.equals(rootHash)) {
			byte[] rootBytes=rootHash.getBytes();
			System.arraycopy(rootBytes,0,copy,V3_ROOT_HASH_OFFSET,rootBytes.length);
		}
		System.arraycopy(fileSalt,0,copy,V3_FILE_SALT_OFFSET,fileSalt.length);
		if (publicKeyHint!=null) {
			byte[] hintBytes=publicKeyHint.getBytes();
			System.arraycopy(hintBytes,0,copy,V3_PUBLIC_KEY_HINT_OFFSET,hintBytes.length);
		}
		Utils.writeLong(copy,V3_CLOSE_STATE_OFFSET,closeState);
		byte[] check=calculateCheck(copy,cipherId,headerMacKey);
		System.arraycopy(check,0,copy,V3_HEADER_CHECK_OFFSET,check.length);
		return copy;
	}

	private static byte[] calculateCheck(byte[] copy, int cipherId, byte[] macKey) {
		try {
			if (cipherId==V3_CIPHER_NONE) {
				MessageDigest digest=MessageDigest.getInstance(SHA_256);
				digest.update(copy,0,V3_HEADER_PREFIX_SIZE);
				return digest.digest();
			}
			Mac mac=Mac.getInstance(HMAC_SHA_256);
			mac.init(new SecretKeySpec(macKey,HMAC_SHA_256));
			mac.update(copy,0,V3_HEADER_PREFIX_SIZE);
			return mac.doFinal();
		} catch (GeneralSecurityException e) {
			throw new IllegalStateException("Required Etch v3 header-check algorithm is unavailable",e);
		}
	}

	@Override
	void initialise(Etch etch) throws IOException {
		if ((activeCopy>=0)||(etch.getDataLength()!=0L)) {
			throw new IllegalStateException("Cannot initialise an existing Etch v3 file");
		}
		boolean encryptedData=cipherId!=V3_CIPHER_NONE;
		if (encryptedData!=etch.isEncrypted()) {
			throw new IllegalArgumentException("Etch v3 header and data cipher do not agree");
		}
		if (encryptedIndex!=etch.isIndexEncrypted()) {
			throw new IllegalArgumentException("Etch v3 header and index encryption do not agree");
		}

		byte[] emptyHeaders=new byte[V3_HEADER_REGION_SIZE];
		long headerPosition=etch.appendHeader(emptyHeaders,0,emptyHeaders.length);
		if (headerPosition!=0L) throw new IllegalStateException("Unexpected Etch v3 header position");
		long rootPosition=etch.appendZeroIndex(ROOT_INDEX_SIZE*POINTER_SIZE,POINTER_SIZE);
		if (rootPosition!=indexStart()) {
			throw new IllegalStateException("Unexpected Etch v3 root index position: "+rootPosition);
		}

		long initialEnd=etch.getDataLength();
		etch.force();
		writeCopy(etch,0,0L,initialEnd,rootHash,V3_OPEN);
		forceCopy(etch,0);
		writeCopy(etch,1,1L,initialEnd,rootHash,V3_OPEN);
		forceCopy(etch,1);
		generation=1L;
		syncedFileEnd=initialEnd;
		closeState=V3_OPEN;
		activeCopy=1;
	}

	private void writeCopy(Etch etch, int copyIndex, long nextGeneration,
			long nextSyncedEnd, Hash nextRoot, long nextCloseState) throws IOException {
		byte[] encoded=encode(nextGeneration,nextSyncedEnd,nextRoot,nextCloseState);
		etch.writeHeader(copyPosition(copyIndex),encoded,0,encoded.length);
	}

	private void forceCopy(Etch etch, int copyIndex) throws IOException {
		etch.forceHeader(copyPosition(copyIndex),V3_HEADER_COPY_SIZE);
	}

	private static long copyPosition(int copyIndex) {
		return (copyIndex==0)?V3_HEADER_A_OFFSET:V3_HEADER_B_OFFSET;
	}

	@Override
	Hash getRootHash() {
		return rootHash;
	}

	@Override
	void setRootHash(Etch etch, Hash rootHash) {
		this.rootHash=Objects.requireNonNull(rootHash,"rootHash");
	}

	@Override
	void prepareMutation(Etch etch) throws IOException {
		if (closeState==V3_CLEAN_CLOSED) commitHeaderOnly(etch,V3_OPEN);
	}

	@Override
	void writeDataLength(Etch etch) {
		// V3 records writeEnd only as part of an ordered header commit in sync().
	}

	@Override
	void sync(Etch etch) throws IOException {
		if (closeState==V3_CLEAN_CLOSED) return;
		commit(etch,V3_OPEN);
	}

	@Override
	void close(Etch etch) throws IOException {
		try {
			if (closeState!=V3_CLEAN_CLOSED) commit(etch,V3_CLEAN_CLOSED);
		} finally {
			destroy();
		}
	}

	@Override
	void destroy() {
		if (headerMacKey!=null) Arrays.fill(headerMacKey,(byte)0);
	}

	private void commit(Etch etch, long nextCloseState)
			throws IOException {
		commit(etch,nextCloseState,true);
	}

	private void commitHeaderOnly(Etch etch, long nextCloseState)
			throws IOException {
		commit(etch,nextCloseState,false);
	}

	private void commit(Etch etch, long nextCloseState, boolean forceBody)
			throws IOException {
		if (activeCopy<0) throw new IllegalStateException("Etch v3 header is not initialised");
		if (generation==UNSIGNED_MAX_GENERATION) {
			throw new IOException("Etch v3 header generation is exhausted; migration required");
		}
		long nextGeneration=generation+1L;
		long nextSyncedEnd=etch.getDataLength();
		int nextCopy=1-activeCopy;

		if (forceBody) etch.force();
		writeCopy(etch,nextCopy,nextGeneration,nextSyncedEnd,rootHash,nextCloseState);
		forceCopy(etch,nextCopy);

		generation=nextGeneration;
		syncedFileEnd=nextSyncedEnd;
		closeState=nextCloseState;
		activeCopy=nextCopy;
	}

	int cipherId() {
		return cipherId;
	}

	boolean isIndexEncrypted() {
		return encryptedIndex;
	}

	long generation() {
		return generation;
	}

	long syncedFileEnd() {
		return syncedFileEnd;
	}

	long closeState() {
		return closeState;
	}

	int activeCopy() {
		return activeCopy;
	}

	boolean isDegraded() {
		return degraded;
	}

	boolean isCleanClosed() {
		return closeState==V3_CLEAN_CLOSED;
	}

	byte[] fileSalt() {
		return fileSalt.clone();
	}

	@Override
	AccountKey publicKeyHint() {
		return publicKeyHint;
	}

	private record Validation(Candidate candidate, IOException failure) {
	}

	private record Candidate(int cipherId, boolean encryptedIndex, long generation,
			long syncedFileEnd, long indexStart, Hash rootHash, byte[] fileSalt,
			AccountKey publicKeyHint,
			long closeState, int copyIndex) {
		boolean sameImmutableFields(Candidate other) {
			return (cipherId==other.cipherId)&&(encryptedIndex==other.encryptedIndex)
					&&(indexStart==other.indexStart)&&Arrays.equals(fileSalt,other.fileSalt)
					&&Objects.equals(publicKeyHint,other.publicKeyHint);
		}

		EtchV3Header toHeader(boolean degraded, byte[] macKey) {
			return new EtchV3Header(cipherId,encryptedIndex,generation,syncedFileEnd,
					indexStart,rootHash,fileSalt,publicKeyHint,closeState,copyIndex,degraded,macKey);
		}
	}
}
