package convex.etch;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.util.Arrays;

import org.junit.jupiter.api.Test;

import convex.core.data.AccountKey;
import convex.core.data.Hash;
import convex.core.util.Utils;

/** Byte-exact and recovery-selection tests for the fixed Etch v3 header. */
public class EtchV3HeaderTest {
	private static final long INITIAL_FILE_END=EtchConstants.V3_INDEX_START
			+(long)EtchConstants.ROOT_INDEX_SIZE*EtchConstants.POINTER_SIZE;

	private static final String PLAIN_CHECK_A=
			"4d7273e7e966670d52bba0de6abc09cea80e3a1af8c17cd588b8570abf80c6c2";
	private static final String PLAIN_CHECK_B=
			"fb9de2ca9d735e0cd8ad8cdf88486776221479edee12f9c47e123d70c613e518";
	private static final String AES_CHECK_A=
			"0a43c34f97035b62c43d0588870b44160d264e36dfa6e5e9100aeb91d8810c7e";
	private static final String AES_CHECK_B=
			"d14dd1cf54a2178c887b6e7865636f439607a713115b2f7adc2031594cc6129e";

	@Test
	public void testCanonicalPlaintextFile() throws Exception {
		byte[] salt=sequence(0xa0,EtchConstants.V3_FILE_SALT_SIZE);
		CanonicalFile file=createCanonical(EtchConstants.V3_CIPHER_NONE,false,
				salt,null,null);

		assertCanonicalCopy(file.copyA(),EtchConstants.V3_CIPHER_NONE,0L,
				salt,null,PLAIN_CHECK_A);
		assertCanonicalCopy(file.copyB(),EtchConstants.V3_CIPHER_NONE,1L,
				salt,null,PLAIN_CHECK_B);
		assertZero(file.bytes(),Math.toIntExact(EtchConstants.V3_INDEX_START),file.bytes().length);

		EtchV3Header selected=EtchV3Header.select(file.copyA(),file.copyB(),null,"memory-v3");
		assertSelectedInitialHeader(selected,1L,1,false,null);
	}

	@Test
	public void testCanonicalAES256CTRFileAndKeyVerification() throws Exception {
		byte[] secret=sequence(0x00,32);
		byte[] salt=sequence(0xa0,EtchConstants.V3_FILE_SALT_SIZE);
		AccountKey publicKeyHint=AccountKey.wrap(sequence(0x20,AccountKey.LENGTH));
		CanonicalFile file=createCanonical(EtchConstants.V3_CIPHER_AES_256_CTR,
				false,salt,publicKeyHint,secret);

		assertCanonicalCopy(file.copyA(),EtchConstants.V3_CIPHER_AES_256_CTR,
				0L,salt,publicKeyHint,AES_CHECK_A);
		assertCanonicalCopy(file.copyB(),EtchConstants.V3_CIPHER_AES_256_CTR,
				1L,salt,publicKeyHint,AES_CHECK_B);
		// The configured plaintext root index remains a canonical zero index.
		assertZero(file.bytes(),Math.toIntExact(EtchConstants.V3_INDEX_START),file.bytes().length);

		EtchV3Header selected=EtchV3Header.select(file.copyA(),file.copyB(),secret,"memory-aes");
		assertSelectedInitialHeader(selected,1L,1,false,publicKeyHint);
		assertEquals(EtchConstants.V3_CIPHER_AES_256_CTR,selected.cipherId());
		assertThrows(IOException.class,()->EtchV3Header.select(file.copyA(),file.copyB(),
				sequence(0x40,32),"memory-aes"));
	}

	@Test
	public void testFallsBackFromDamagedNewestCopy() throws Exception {
		byte[] salt=sequence(0xa0,EtchConstants.V3_FILE_SALT_SIZE);
		CanonicalFile file=createCanonical(EtchConstants.V3_CIPHER_NONE,false,
				salt,null,null);
		byte[] damagedB=file.copyB().clone();
		damagedB[EtchConstants.V3_HEADER_PREFIX_SIZE]=1;

		EtchV3Header selected=EtchV3Header.select(file.copyA(),damagedB,null,"memory-damaged");
		assertSelectedInitialHeader(selected,0L,0,true,null);
	}

	@Test
	public void testSyncAndCleanCloseAlternateHeaderCopies() throws Exception {
		byte[] salt=sequence(0xa0,EtchConstants.V3_FILE_SALT_SIZE);
		InMemoryEtchFileMapper mapper=new InMemoryEtchFileMapper();
		try (EtchFileAccess access=new EtchFileAccess(mapper,"memory-sync",0L,0L)) {
			EtchV3Header header=EtchV3Header.create(EtchConstants.V3_CIPHER_NONE,
					false,salt,null);
			header.initialise(access);
			assertEquals(3,mapper.forceCount());

			Hash root=hashSequence(1);
			header.setRootHash(access,root);
			access.appendIndex(new byte[] { 42 },0,1,1);
			header.sync(access);
			assertEquals(5,mapper.forceCount());
			assertEquals(2L,header.generation());
			assertEquals(0,header.activeCopy());
			assertEquals(INITIAL_FILE_END+1L,header.syncedFileEnd());

			byte[] syncedA=mapper.copyRange(EtchConstants.V3_HEADER_A_OFFSET,
					EtchConstants.V3_HEADER_A_OFFSET+EtchConstants.V3_HEADER_COPY_SIZE);
			byte[] previousB=mapper.copyRange(EtchConstants.V3_HEADER_B_OFFSET,
					EtchConstants.V3_HEADER_B_OFFSET+EtchConstants.V3_HEADER_COPY_SIZE);
			EtchV3Header reopened=EtchV3Header.select(syncedA,previousB,null,"memory-sync");
			assertEquals(2L,reopened.generation());
			assertEquals(root,reopened.getRootHash(null));
			assertEquals(INITIAL_FILE_END+1L,reopened.syncedFileEnd());

			header.close(access);
			assertEquals(7,mapper.forceCount());
			assertEquals(3L,header.generation());
			assertEquals(1,header.activeCopy());
			assertEquals(EtchConstants.V3_CLEAN_CLOSED,header.closeState());
		}
	}

	@Test
	public void testRejectsAmbiguousEqualGenerationCopies() {
		byte[] salt=sequence(0xa0,EtchConstants.V3_FILE_SALT_SIZE);
		EtchV3Header header=EtchV3Header.create(EtchConstants.V3_CIPHER_NONE,false,salt,null);
		byte[] first=header.encode(7L,INITIAL_FILE_END,Hash.UNSET_HASH,EtchConstants.V3_OPEN);
		byte[] second=header.encode(7L,INITIAL_FILE_END,hashSequence(1),EtchConstants.V3_OPEN);

		assertThrows(IOException.class,
				()->EtchV3Header.select(first,second,null,"memory-ambiguous"));
	}

	@Test
	public void testRejectsImmutableDisagreement() {
		byte[] salt=sequence(0xa0,32);
		EtchV3Header firstHeader=EtchV3Header.create(EtchConstants.V3_CIPHER_NONE,
				false,salt,AccountKey.wrap(sequence(1,32)),null);
		EtchV3Header secondHeader=EtchV3Header.create(EtchConstants.V3_CIPHER_NONE,
				false,salt,AccountKey.wrap(sequence(2,32)),null);
		byte[] first=firstHeader.encode(1L,INITIAL_FILE_END,Hash.UNSET_HASH,EtchConstants.V3_OPEN);
		byte[] second=secondHeader.encode(2L,INITIAL_FILE_END,Hash.UNSET_HASH,EtchConstants.V3_OPEN);

		assertThrows(IOException.class,
				()->EtchV3Header.select(first,second,null,"memory-disagreement"));
	}

	@Test
	public void testZeroPublicKeyHintMeansUnset() throws Exception {
		byte[] salt=sequence(0xa0,32);
		EtchV3Header header=EtchV3Header.create(EtchConstants.V3_CIPHER_NONE,
				false,salt,AccountKey.ZERO,null);
		byte[] copy=header.encode(0L,INITIAL_FILE_END,Hash.UNSET_HASH,EtchConstants.V3_OPEN);

		assertZero(copy,EtchConstants.V3_PUBLIC_KEY_HINT_OFFSET,
				EtchConstants.V3_PUBLIC_KEY_HINT_OFFSET+EtchConstants.V3_PUBLIC_KEY_HINT_SIZE);
		EtchV3Header selected=EtchV3Header.select(copy,copy,null,"memory-zero-hint");
		assertNull(selected.publicKeyHint());
	}

	@Test
	public void testSelectsHighestUnsignedGeneration() throws Exception {
		EtchV3Header header=EtchV3Header.create(EtchConstants.V3_CIPHER_NONE,
				false,sequence(0xa0,32),null);
		byte[] signedPositive=header.encode(Long.MAX_VALUE,INITIAL_FILE_END,
				Hash.UNSET_HASH,EtchConstants.V3_OPEN);
		byte[] unsignedHigher=header.encode(Long.MIN_VALUE,INITIAL_FILE_END,
				Hash.UNSET_HASH,EtchConstants.V3_OPEN);

		EtchV3Header selected=EtchV3Header.select(signedPositive,unsignedHigher,
				null,"memory-unsigned");
		assertEquals(Long.MIN_VALUE,selected.generation());
		assertEquals(1,selected.activeCopy());
	}

	private static CanonicalFile createCanonical(int cipherId, boolean encryptedIndex,
			byte[] salt, AccountKey publicKeyHint, byte[] secret) throws Exception {
		InMemoryEtchFileMapper mapper=new InMemoryEtchFileMapper();
		EtchFileCipher cipher=(cipherId==EtchConstants.V3_CIPHER_NONE)
				?null:AES256CTREtchCipher.derive(secret,salt);
		try (EtchFileAccess access=new EtchFileAccess(mapper,"memory-v3",0L,0L,
				cipher,encryptedIndex)) {
			EtchV3Header header=EtchV3Header.create(cipherId,encryptedIndex,salt,
					publicKeyHint,secret);
			header.initialise(access);
			assertEquals(INITIAL_FILE_END,access.getDataLength());
			assertSelectedInitialHeader(header,1L,1,false,publicKeyHint);
			byte[] bytes=mapper.copyOf(INITIAL_FILE_END);
			return new CanonicalFile(bytes,
					Arrays.copyOfRange(bytes,0,EtchConstants.V3_HEADER_COPY_SIZE),
					Arrays.copyOfRange(bytes,EtchConstants.V3_HEADER_COPY_SIZE,
							EtchConstants.V3_HEADER_REGION_SIZE));
		}
	}

	private static void assertCanonicalCopy(byte[] copy, int cipherId, long generation,
			byte[] salt, AccountKey publicKeyHint, String expectedCheck) {
		assertEquals(EtchConstants.V3_HEADER_COPY_SIZE,copy.length);
		assertEquals(EtchConstants.MAGIC_NUMBER,Utils.readShort(copy,0)&0xffff);
		assertEquals(EtchConstants.VERSION_3,Utils.readShort(copy,2));
		assertEquals(cipherId,Utils.readShort(copy,EtchConstants.V3_CIPHER_OFFSET)&0xffff);
		assertEquals(EtchConstants.V3_INDEX_PLAINTEXT,
				Utils.readShort(copy,EtchConstants.V3_INDEX_ENCRYPTION_OFFSET)&0xffff);
		assertEquals(generation,Utils.readLong(copy,EtchConstants.V3_GENERATION_OFFSET,Long.BYTES));
		assertEquals(INITIAL_FILE_END,
				Utils.readLong(copy,EtchConstants.V3_SYNCED_FILE_END_OFFSET,Long.BYTES));
		assertEquals(EtchConstants.V3_INDEX_START,
				Utils.readLong(copy,EtchConstants.V3_INDEX_START_OFFSET,Long.BYTES));
		assertZero(copy,EtchConstants.V3_ROOT_HASH_OFFSET,EtchConstants.V3_FILE_SALT_OFFSET);
		assertArrayEquals(salt,Arrays.copyOfRange(copy,EtchConstants.V3_FILE_SALT_OFFSET,
				EtchConstants.V3_PUBLIC_KEY_HINT_OFFSET));
		byte[] actualHint=Arrays.copyOfRange(copy,EtchConstants.V3_PUBLIC_KEY_HINT_OFFSET,
				EtchConstants.V3_CLOSE_STATE_OFFSET);
		if (publicKeyHint==null) {
			assertZero(actualHint,0,actualHint.length);
		} else {
			assertArrayEquals(publicKeyHint.getBytes(),actualHint);
		}
		assertEquals(EtchConstants.V3_OPEN,
				Utils.readLong(copy,EtchConstants.V3_CLOSE_STATE_OFFSET,Long.BYTES));
		assertZero(copy,EtchConstants.V3_HEADER_PREFIX_SIZE,
				EtchConstants.V3_HEADER_CHECK_OFFSET);
		assertEquals(expectedCheck,Utils.toHexString(Arrays.copyOfRange(copy,
				EtchConstants.V3_HEADER_CHECK_OFFSET,EtchConstants.V3_HEADER_COPY_SIZE)));
	}

	private static void assertSelectedInitialHeader(EtchV3Header header,
			long generation, int activeCopy, boolean degraded, AccountKey publicKeyHint) {
		assertEquals(EtchConstants.VERSION_3,header.version());
		assertEquals(EtchConstants.V3_INDEX_START,header.indexStart());
		assertEquals(INITIAL_FILE_END,header.syncedFileEnd());
		assertEquals(Hash.UNSET_HASH,header.getRootHash(null));
		assertEquals(EtchConstants.V3_OPEN,header.closeState());
		assertEquals(generation,header.generation());
		assertEquals(activeCopy,header.activeCopy());
		assertEquals(degraded,header.isDegraded());
		assertFalse(header.isIndexEncrypted());
		assertTrue(header.fileSalt()[0]!=0);
		assertEquals(publicKeyHint,header.publicKeyHint());
	}

	private static Hash hashSequence(int first) {
		return Hash.wrap(sequence(first,Hash.LENGTH));
	}

	private static byte[] sequence(int first, int length) {
		byte[] result=new byte[length];
		for (int i=0;i<length;i++) result[i]=(byte)(first+i);
		return result;
	}

	private static void assertZero(byte[] data, int start, int end) {
		for (int i=start;i<end;i++) {
			if (data[i]!=0) fail("Expected canonical zero byte at offset "+i);
		}
	}

	private record CanonicalFile(byte[] bytes, byte[] copyA, byte[] copyB) {
	}
}
