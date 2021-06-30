package convex.core.data;

import java.nio.ByteBuffer;

import convex.core.crypto.AKeyPair;
import convex.core.crypto.ASignature;
import convex.core.crypto.Ed25519Signature;
import convex.core.exceptions.BadFormatException;
import convex.core.exceptions.BadSignatureException;
import convex.core.exceptions.InvalidDataException;
import convex.core.transactions.ATransaction;

/**
 * Node representing a signed data object.
 *
 * A signed data object encapsulates:
 * <ul>
 * <li>An Address that identifies the signer</li>
 * <li>A digital signature </li>
 * <li>An underlying data data object that has been signed.</li>
 * </ul>
 *
 * The SignedData instance is considered <b>valid</b> if the signature can be successfully validated for
 * the given Address and data object, and if so can be taken as a cryptographic proof that the signature
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
public class SignedData<T extends ACell> extends ACell {
	// Encoded fields
	private final AccountKey publicKey;
	private final ASignature signature;
	private final Ref<T> valueRef;

	/**
	 * Validated flag. Not part of data representation: serves to avoid unnecessary re-validation.
	 */
	private boolean validated;

	private SignedData(Ref<T> ref, AccountKey address, ASignature sig, boolean validated) {
		this.valueRef = ref;
		this.publicKey = address;
		signature = sig;
		this.validated=validated;
	}

	private SignedData(Ref<T> ref, AccountKey address, ASignature sig) {
		this(ref,address,sig,false); // SECURITY: assume not validated unless specified
	}

	/**
	 * Signs a data value Ref with the given keypair.
	 *
	 * @param keyPair The public/private key pair of the signer.
	 * @param ref     Ref to the data to sign
	 * @return SignedData object signed with the given key-pair
	 */
	public static <T extends ACell> SignedData<T> createWithRef(AKeyPair keyPair, Ref<T> ref) {
		ASignature sig = keyPair.sign(ref.getHash());
		SignedData<T> sd = new SignedData<T>(ref, keyPair.getAccountKey(), sig);
		sd.validated = true; // validate stuff we have just signed by default
		return sd;
	}

	public static <T extends ACell> SignedData<T> create(AKeyPair keyPair, T value2) {
		return createWithRef(keyPair, Ref.get(value2));
	}

	/**
	 * Creates a SignedData object with the given parameters. Not assumed to be valid.
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
	 * @return Data value that has been signed
	 * @throws BadSignatureException
	 */
	public T getValue() throws BadSignatureException {
		validateSignature();
		return valueRef.getValue();
	}

	/**
	 * Gets the value object encapsulated by this SignedData object, without
	 * checking if the signature is correct
	 *
	 * @return Data value that has been signed
	 */
	public T getValueUnchecked() {
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
	public int encode(byte[] bs, int pos) {
		bs[pos++]=Tag.SIGNED_DATA;
		return encodeRaw(bs,pos);
	}

	@Override
	public int encodeRaw(byte[] bs, int pos) {
		pos = publicKey.encodeRaw(bs,pos);
		pos = signature.encodeRaw(bs,pos);
		pos = valueRef.encode(bs,pos);
		return pos;
	}

	@Override
	public int estimatedEncodingSize() {
		return 1+AccountKey.LENGTH+Ed25519Signature.SIGNATURE_LENGTH+Format.MAX_EMBEDDED_LENGTH;
	}

	/**
	 * Reads a SignedData instance from the given ByteBuffer
	 *
	 * @param data A ByteBuffer containing
	 * @return A SignedData object
	 * @throws BadFormatException
	 */
	public static <T extends ACell> SignedData<T> read(ByteBuffer data) throws BadFormatException {
		// header already assumed to be consumed
		AccountKey address = AccountKey.readRaw(data);
		ASignature sig = ASignature.read(data);
		Ref<T> value = Format.readRef(data);
		return create(address, sig, value);
	}

	/**
	 * Validates the signature in this SignedData instance.
	 *
	 * @return true if valid, false otherwise
	 */
	public boolean checkSignature() {
		if (validated) return true;
		Hash hash=valueRef.getHash();
		boolean check = signature.verify(hash, publicKey);
		validated=check;
		return check;
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
	public int getRefCount() {
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
		// SECURITY: preserve validated flag
		return new SignedData<T>(newValueRef, publicKey, signature, validated);
	}

	@Override
	public void ednString(StringBuilder sb) {
		sb.append("#signeddata {");
		sb.append(":data "+valueRef.getHash().toString());
		sb.append("}");
	}

	@Override
	public void print(StringBuilder sb) {
		sb.append("{");
		sb.append(":signed "+valueRef.getHash().toString());
		sb.append("}");
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

	public Ref<T> getDataRef() {
		return valueRef;
	}
	
	/**
	 * SignedData is not embedded. We want to persist in store always to cache verification status
	 *
	 * @return Always false
	 */
	public boolean isEmbedded() {
		return false;
	}

	/**
	 * Checks if this SignedData has a valid signature.
	 *
	 * @return true if the Signature is valid for the given data, false otherwise.
	 */
	public boolean isValid() {
		return signature.verify(valueRef.getHash(), publicKey);
	}

	@Override
	public byte getTag() {
		return Tag.SIGNED_DATA;
	}
}
