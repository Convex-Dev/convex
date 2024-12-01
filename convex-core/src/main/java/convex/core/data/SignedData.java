package convex.core.data;

import convex.core.crypto.AKeyPair;
import convex.core.crypto.ASignature;
import convex.core.crypto.Ed25519Signature;
import convex.core.crypto.Providers;
import convex.core.cvm.ACVMRecord;
import convex.core.cvm.Keywords;
import convex.core.cvm.RecordFormat;
import convex.core.data.util.BlobBuilder;
import convex.core.exceptions.BadFormatException;
import convex.core.exceptions.BadSignatureException;
import convex.core.exceptions.InvalidDataException;
import convex.core.lang.RT;

/**
 * Node representing a signed data object.
 *
 * A signed data object encapsulates:
 * <ul>
 * <li>An Address that identifies the signer</li>
 * <li>A digital signature </li>
 * <li>An underlying Cell that has been signed.</li>
 * </ul>
 *
 * The SignedData instance is considered <b>valid</b> if the signature can be successfully validated for
 * the given Address and data value, and if so can be taken as a cryptographic proof that the signature
 * was created by someone in possession of the corresponding private key.
 *
 * Note we currently go via a Ref here for a few reasons: - It guarantees we
 * have a hash for signing - It makes the SignedData object
 * implementation/representation independent of the value type - It creates a
 * possibility of structural sharing for transaction values excluding signatures
 *
 * Binary representation:
 * <ol>
 * <li>1 byte - Tag.SIGNED_DATA tag </li>
 * <li>32 bytes - Public Key of signer</li>
 * <li>64 bytes - raw Signature data</li>
 * <li>1+ bytes - Data Value Ref (may be embedded)</li>
 * </ol>
 *
 * SECURITY: signing requires presence of a local keypair TODO: SECURITY: any
 * persistence forces validation of Signature??
 *
 * @param <T> The type of the signed object
 */
public final class SignedData<T extends ACell> extends ACVMRecord {
	// Encoded fields
	private final AccountKey pubKey;
	private final ASignature signature;
	private final Ref<T> valueRef;

	private static final Keyword[] KEYS = new Keyword[] { Keywords.PUBLIC_KEY, Keywords.SIGNATURE, Keywords.VALUE };

	private static final RecordFormat FORMAT = RecordFormat.of(KEYS);
	private static final StringShort SIGNED_TAG = StringShort.create("#Signed");
	
	//Cached fields
	private AccountKey verifiedKey=null;

	private SignedData(Ref<T> refToValue, AccountKey address, ASignature sig) {
		super(Tag.SIGNED_DATA,FORMAT.count());
		this.valueRef = refToValue;
		this.pubKey = address;
		signature = sig;
	}
	
	/**
	 * Signs a data value Ref with the given keypair. 
	 * 
	 * SECURITY: Marks as already validated, since we just signed it.
	 *
	 * @param keyPair The public/private key pair of the signer.
	 * @param ref     Ref to the data to sign
	 * @return SignedData object signed with the given key-pair
	 */
	public static <T extends ACell> SignedData<T> signRef(AKeyPair keyPair, Ref<T> ref) {
		Blob message=getMessageForRef(ref);
		ASignature sig = keyPair.sign(message);
		SignedData<T> sd = new SignedData<T>(ref, keyPair.getAccountKey(), sig);
		sd.markValidated();
		return sd;
	}

	public static <T extends ACell> Blob getMessageForRef(Ref<T> ref) {
		return ref.getEncoding();
	}

	/**
	 * Mark this SignedData as already verified as good - cache in Ref
	 */
	private void markValidated() {
		Ref<ACell> ref=getRef();
		int flags=ref.getFlags();
		if ((flags&Ref.VERIFIED_MASK)!=0) return; // already done
		ref.setFlags(flags|Ref.VERIFIED_MASK);
	}
	
	/**
	 * Mark this SignedData as a bad signature - cache in Ref
	 */
	private void markBadSignature() {
		Ref<ACell> ref=getRef();
		int flags=ref.getFlags();
		if ((flags&Ref.BAD_MASK)!=0) return; // already done
		ref.setFlags(flags|Ref.BAD_MASK);
	}

	/**
	 * Create a SignedData by signing a value with the given key pair
	 * @param <T> Type of value to sign
	 * @param keyPair Key pair to sign with
	 * @param value Any cell value to sign
	 * @return A new SignedData instance
	 */
	public static <T extends ACell> SignedData<T> sign(AKeyPair keyPair, T value) {
		return signRef(keyPair, Ref.get(value));
	}

	/**
	 * Creates a SignedData object with the given parameters. The resulting SignedData will be a short version without the public key.
	 *
	 * @param <T> Type of value to sign
	 * @param sig     Signature of the supplied data
	 * @param ref     Ref to the data that has been signed
	 * @return A new SignedData instance
	 */
	public static <T extends ACell> SignedData<T> create(ASignature sig, Ref<T> ref) {
		return create(null,sig,ref);
	}
	
	/**
	 * Creates a SignedData object with the given parameters. 
	 * 
	 * SECURITY: Not assumed to be valid.
	 *
	 * @param <T> Type of value to sign
	 * @param address Public key of the signer
	 * @param sig     Signature of the supplied data
	 * @param ref     Ref to the data that has been signed
	 * @return A new SignedData instance
	 */
	public static <T extends ACell> SignedData<T> create(AccountKey address, ASignature sig, Ref<T> ref) {
		return new SignedData<T>(ref, address, sig);
	}

	/**
	 * Gets the signed value object encapsulated by this SignedData object.
	 * 
	 * Does not check Signature.
	 *
	 * @return Data value that has been signed
	 */
	public T getValue()  {
		return valueRef.getValue();
	}


	/**
	 * Gets the public key of the signer. If the signature is valid, this
	 * represents a cryptographic proof that the signer was in possession of the
	 * private key of this address.
	 *
	 * @return Public Key of signer.
	 */
	public AccountKey getAccountKey() {
		return pubKey;
	}

	/**
	 * Gets the Signature that formed part of this SignedData object
	 *
	 * @return Signature instance
	 */
	public ASignature getSignature() {
		return signature;
	}

	@Override
	public ACell get(Keyword key) {
		if (Keywords.PUBLIC_KEY.equals(key)) return pubKey;
		if (Keywords.SIGNATURE.equals(key)) return signature;
		if (Keywords.VALUE.equals(key)) return getValue();
		
		return null;
	}

	@Override
	public int encode(byte[] bs, int pos) {
		byte tag=Tag.SIGNED_DATA;
		bs[pos++]=tag;
		return encodeRaw(bs,pos);
	}

	@Override
	public int encodeRaw(byte[] bs, int pos) {
		pos = pubKey.getBytes(bs,pos);
		pos = signature.getBytes(bs, pos);
		pos = valueRef.encode(bs,pos);
		return pos;
	}
	
	@Override
	public boolean print(BlobBuilder sb, long limit) {
		sb.append(SIGNED_TAG);
		sb.append(' ');
		return super.print(sb,limit);
	}


	@Override
	public int estimatedEncodingSize() {
		return 1+AccountKey.LENGTH+Ed25519Signature.SIGNATURE_LENGTH+Format.MAX_EMBEDDED_LENGTH;
	}

	/**
	 * Reads a SignedData instance from the given Blob encoding
	 *
	 * @param b Blob to read from
	 * @param pos Start position in Blob (location of tag byte)
	 * @return New decoded instance
	 * @throws BadFormatException In the event of any encoding error
	 */
	public static <T extends ACell> SignedData<T>  read(Blob b, int pos, boolean includeKey) throws BadFormatException {
		int epos=pos+1; // skip tag
		
		AccountKey pubKey;
		if (includeKey) {
			pubKey=AccountKey.readRaw(b,epos);
			epos+=AccountKey.LENGTH;
		} else {
			pubKey=null;
		}
		
		ASignature sig = Ed25519Signature.readRaw(b,epos);
		epos+=Ed25519Signature.SIGNATURE_LENGTH;
		
		Ref<T> value=Format.readRef(b, epos);
		epos+=value.getEncodingLength();
		
		SignedData<T> result=create(pubKey, sig, value);
		Blob enc=b.slice(pos, epos);
		result.attachEncoding(enc);
		return result;
	}

	/**
	 * Validates the signature in this SignedData instance. Caches result
	 *
	 * @return true if valid, false otherwise
	 */
	public boolean checkSignature() {
		if (pubKey==null) return false;
		return checkSignatureImpl(pubKey);
	}
	
	/**
	 * Validates the signature in this SignedData instance. Caches result
	 * 
	 * @param publicKey Public key to check against this signature
	 * @return true if valid, false otherwise
	 */
	public boolean checkSignature(AccountKey publicKey) {
		if ((this.pubKey!=null)&&!(this.pubKey.equals(publicKey))) return false;
		return checkSignatureImpl(publicKey);
	}
	
	/**
	 * Validates the signature in this SignedData instance. Caches result
	 *
	 * @return true if valid, false otherwise
	 */
	private synchronized boolean checkSignatureImpl(AccountKey publicKey) {
		// Fast check for already verified key
		if (verifiedKey!=null) return verifiedKey.equals(publicKey);
		
		Ref<SignedData<T>> sigRef=getRef();
		int flags=sigRef.getFlags();
		if ((flags&Ref.BAD_MASK)!=0) return false;
		if ((flags&Ref.VERIFIED_MASK)!=0) return true;

		Blob message=getMessage();
		boolean check = Providers.verify(signature,message, publicKey);

		if (check) {
			markValidated();
			verifiedKey=publicKey;
		} else {
			markBadSignature();
		}
		return check;
	}
	
	/**
	 * Gets the message bytes (as signed in this SignedData)
	 * @return
	 */
	private Blob getMessage() {
		Blob b=getEncoding();
		int offset=1+((pubKey==null)?64:96);
		return b.slice(offset);
	}


	/**
	 * Checks if the signature has already gone through verification. MAy or may 
	 * not be a valid signature.
	 *
	 * @return true if valid, false otherwise
	 */
	public boolean isSignatureChecked() {
		Ref<SignedData<T>> sigRef=getRef();
		int flags=sigRef.getFlags();
		return (flags&(Ref.BAD_MASK|Ref.VERIFIED_MASK))!=0;
	}

	public void validateSignature() throws BadSignatureException {
		if (!checkSignature()) throw new BadSignatureException("Signature not valid!", this);
	}

	@Override
	public boolean isCanonical() {
		return true;
	}

	@Override
	public final int getRefCount() {
		// Value Ref only
		return 1;
	}

	@SuppressWarnings("unchecked")
	@Override
	public <R extends ACell> Ref<R> getRef(int i) {
		if (i != 0) throw new IndexOutOfBoundsException("Illegal SignedData ref index: " + i);
		return (Ref<R>) valueRef;
	}

	@Override
	public SignedData<T> updateRefs(IRefFunction func) {
		@SuppressWarnings("unchecked")
		Ref<T> newValueRef = (Ref<T>) func.apply(valueRef);
		if (valueRef == newValueRef) return this;
		
		// SECURITY: preserve verification flags
		SignedData<T> newSD= new SignedData<T>(newValueRef, pubKey, signature);
		Ref<?> sdr=newSD.getRef();
		sdr.setFlags(Ref.mergeFlags(sdr.getFlags(), getRef().getFlags()));
		newSD.attachEncoding(encoding); // optimisation to keep encoding
		return newSD;
	}

	@Override
	public void validate() throws InvalidDataException {
		super.validate();
	}

	@Override
	public void validateCell() throws InvalidDataException {
		if (pubKey!=null) pubKey.validate();
		signature.validate();
		valueRef.validate();
	}

	public Ref<T> getValueRef() {
		return valueRef;
	}
	
	/**
	 * SignedData is not embedded. 
	 * main reason: We always want to persist in store to cache verification status
	 *
	 * @return Always false
	 */
	public boolean isEmbedded() {
		return false;
	}

	@Override
	public RecordFormat getFormat() {
		return FORMAT;
	}

	@Override
	public boolean equals(ACell o) {
		if (!(o instanceof SignedData)) return false;
		@SuppressWarnings("unchecked")
		SignedData<T> b=(SignedData<T>) o;
		if (!signature.equals(b.signature)) return false;
		if (!Cells.equals(pubKey,b.pubKey)) return false;
		
		return valueRef.equals(b.valueRef);
	}

	@SuppressWarnings("unchecked")
	public static <T extends ACell> SignedData<T> fromData(AHashMap<Keyword, ACell> value) {
		Ref<T> ref=Ref.get((T)(value.get(Keywords.VALUE)));
		AccountKey key=AccountKey.parse(value.get(Keywords.PUBLIC_KEY));
		ASignature sig=ASignature.fromBlob(RT.ensureBlob(value.get(Keywords.SIGNATURE)));
		return create(key,sig,ref);
	}
}
