package convex.core.data;

import convex.core.crypto.AKeyPair;
import convex.core.crypto.ASignature;
import convex.core.crypto.Ed25519Signature;
import convex.core.crypto.Providers;
import convex.core.exceptions.BadFormatException;
import convex.core.exceptions.BadSignatureException;
import convex.core.exceptions.InvalidDataException;
import convex.core.lang.impl.RecordFormat;
import convex.core.transactions.ATransaction;

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
public final class SignedData<T extends ACell> extends ARecord {
	// Encoded fields
	private final AccountKey publicKey;
	private final ASignature signature;
	private final Ref<T> valueRef;

	private static final Keyword[] KEYS = new Keyword[] { Keywords.PUBLIC_KEY, Keywords.SIGNATURE, Keywords.VALUE };

	private static final RecordFormat FORMAT = RecordFormat.of(KEYS);

	private SignedData(Ref<T> refToValue, AccountKey address, ASignature sig) {
		super(FORMAT.count());
		this.valueRef = refToValue;
		this.publicKey = address;
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
	public static <T extends ACell> SignedData<T> createWithRef(AKeyPair keyPair, Ref<T> ref) {
		ASignature sig = keyPair.sign(ref.getHash());
		SignedData<T> sd = new SignedData<T>(ref, keyPair.getAccountKey(), sig);
		sd.markValidated();
		return sd;
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


	public static <T extends ACell> SignedData<T> create(AKeyPair keyPair, T value2) {
		return createWithRef(keyPair, Ref.get(value2));
	}

	/**
	 * Creates a SignedData object with the given parameters. 
	 * 
	 * SECURITY: Not assumed to be valid.
	 *
	 * @param address Public Address of the signer
	 * @param sig     Signature of the supplied data
	 * @param ref     Ref to the data that has been signed
	 * @return A new SignedData object
	 */
	public static <T extends ACell> SignedData<T> create(AccountKey address, ASignature sig, Ref<T> ref) {
		// boolean check=Sign.verify(ref.getHash(), sig, address);
		// if (!check) throw new ValidationException("Invalid signature: "+sig);
		return new SignedData<T>(ref, address, sig);
	}


	public static SignedData<ATransaction> create(AKeyPair kp, ASignature sig, Ref<ATransaction> ref) {

		return create(kp.getAccountKey(),sig,ref);
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
		return publicKey;
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
		if (Keywords.PUBLIC_KEY.equals(key)) return publicKey;
		if (Keywords.SIGNATURE.equals(key)) return signature;
		if (Keywords.VALUE.equals(key)) return getValue();
		
		return null;
	}

	@Override
	public int encode(byte[] bs, int pos) {
		bs[pos++]=Tag.SIGNED_DATA;
		return encodeRaw(bs,pos);
	}

	@Override
	public int encodeRaw(byte[] bs, int pos) {
		pos = publicKey.getBytes(bs,pos);
		pos = signature.getBytes(bs, pos);
		pos = valueRef.encode(bs,pos);
		return pos;
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
	public static <T extends ACell> SignedData<T>  read(Blob b, int pos) throws BadFormatException {
		int epos=pos+1; // skip tag
		
		AccountKey address=AccountKey.readRaw(b,epos);
		epos+=AccountKey.LENGTH;
		
		ASignature sig = Ed25519Signature.readRaw(b,epos);
		epos+=Ed25519Signature.SIGNATURE_LENGTH;
		
		Ref<T> value=Format.readRef(b, epos);
		epos+=value.getEncodingLength();
		
		SignedData<T> result=create(address, sig, value);
		result.attachEncoding(b.slice(pos, epos));
		return result;
	}

	/**
	 * Validates the signature in this SignedData instance. Caches result
	 *
	 * @return true if valid, false otherwise
	 */
	public boolean checkSignature() {
		Ref<SignedData<T>> sigRef=getRef();
		int flags=sigRef.getFlags();
		if ((flags&Ref.BAD_MASK)!=0) return false;
		if ((flags&Ref.VERIFIED_MASK)!=0) return true;

		Hash hash=valueRef.getHash();
		boolean check = Providers.verify(signature,hash, publicKey);

		if (check) {
			markValidated();
		} else {
			markBadSignature();
		}
		return check;
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

	@Override public final boolean isCVMValue() {
		return false;
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
		SignedData<T> newSD= new SignedData<T>(newValueRef, publicKey, signature);
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
		publicKey.validate();
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
	public byte getTag() {
		return Tag.SIGNED_DATA;
	}

	@Override
	public RecordFormat getFormat() {
		return FORMAT;
	}

}
