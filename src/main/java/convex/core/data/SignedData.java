package convex.core.data;

import java.nio.ByteBuffer;

import convex.core.crypto.AKeyPair;
import convex.core.crypto.ASignature;
import convex.core.crypto.Hash;
import convex.core.exceptions.BadFormatException;
import convex.core.exceptions.BadSignatureException;
import convex.core.exceptions.InvalidDataException;

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
 * <li>1 byte - Message.SIGNED_DATA tag </li>
 * <li>20/32 bytes - Address of signer</li>
 * <li>64 bytes - raw Signature data</li>
 * <li>32 bytes - Data hash (raw Ref)</li>
 * </ol>
 * 
 * SECURITY: signing requires presence of a local keypair TODO: SECURITY: any
 * persistence forces validation of Signature??
 *
 * @param <T> The type of the signed object
 */
public class SignedData<T> extends ACell {
	private final Ref<T> valueRef;
	private final ASignature signature;
	private final Address address;
	
	/**
	 * Validated flag. Not part of data representation: serves to avoid unnecessary re-validation.
	 */
	private boolean validated;

	private SignedData(Ref<T> ref, Address address, ASignature sig, boolean validated) {
		this.valueRef = ref;
		this.address = address;
		signature = sig;
		this.validated=validated;
	}
	
	private SignedData(Ref<T> ref, Address address, ASignature sig) {
		this(ref,address,sig,false); // SECURITY: assume not validated unless specified
	}

	/**
	 * Signs a data value Ref with the given keypair.
	 * 
	 * @param keyPair The public/private key pair of the signer.
	 * @param ref     Ref to the data to sign
	 * @return SignedData object signed with the given key-pair
	 */
	public static <T> SignedData<T> createWithRef(AKeyPair keyPair, Ref<T> ref) {
		ASignature sig = keyPair.sign(ref.getHash());
		SignedData<T> sd = new SignedData<T>(ref, keyPair.getAddress(), sig);
		sd.validated = true; // validate stuff we have just signed by default
		return sd;
	}

	public static <T> SignedData<T> create(AKeyPair keyPair, T value2) {
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
	public static <T> SignedData<T> create(Address address, ASignature sig, Ref<T> ref) {
		// boolean check=Sign.verify(ref.getHash(), sig, address);
		// if (!check) throw new ValidationException("Invalid signature: "+sig);
		return new SignedData<T>(ref, address, sig);
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
	 * Gets the public Address of the signer. If the signature is valid, this
	 * represents a cryptographic proof that the signer was in possession of the
	 * private key of this address.
	 * 
	 * @return Address of signer.
	 */
	public Address getAddress() {
		return address;
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
	public ByteBuffer write(ByteBuffer b) {
		b = b.put(Tag.SIGNED_DATA);
		return writeRaw(b);
	}

	@Override
	public ByteBuffer writeRaw(ByteBuffer b) {
		b = address.writeRaw(b);
		b = signature.writeRaw(b);
		b = valueRef.write(b);
		return b;
	}

	/**
	 * Reads a SignedData instance from the given ByteBuffer
	 * 
	 * @param data A ByteBuffer containing
	 * @return A SignedData object
	 * @throws BadFormatException
	 */
	public static <T> SignedData<T> read(ByteBuffer data) throws BadFormatException {
		// header already assumed to be consumed
		Address address = Address.readRaw(data);
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
		boolean check = signature.verify(hash, address);
		validated=check;
		return check;
	}

	public void validateSignature() throws BadSignatureException {
		if (!checkSignature()) throw new BadSignatureException("Signature not valid!", this);
	}

	@Override
	public int estimatedEncodingSize() {
		// allow for ECDSA/Ed25519 different sizes of Address
		return 99+Address.LENGTH;
	}

	@Override
	public boolean isCanonical() {
		return true;
	}

	@Override
	public int getRefCount() {
		return 1;
	}

	@SuppressWarnings("unchecked")
	@Override
	public <R> Ref<R> getRef(int i) {
		if (i != 0) throw new IndexOutOfBoundsException("Illegal SignedData ref index: " + i);
		return (Ref<R>) valueRef;
	}

	@Override
	public SignedData<T> updateRefs(IRefFunction func) {
		@SuppressWarnings("unchecked")
		Ref<T> newValueRef = (Ref<T>) func.apply(valueRef);
		if (valueRef == newValueRef) return this;
		// SECURITY: preserve validated flag
		return new SignedData<T>(newValueRef, address, signature, validated);
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
		address.validate();
		signature.validate();
		valueRef.validate();
	}

	public Ref<T> getDataRef() {
		return valueRef;
	}
	
	/**
	 * Checks if this SignedData has a valid signature.
	 * 
	 * @return true if the Signature is valid for the given data, false otherwise.
	 */
	public boolean isValid() {
		return signature.verify(valueRef.getHash(), address);
	}

}
