package convex.core.data;

import java.nio.ByteBuffer;

import convex.core.Constants;
import convex.core.exceptions.InvalidDataException;
import convex.core.lang.RT;
import convex.core.util.Errors;
import convex.core.util.Utils;

/**
 * Immutable class representing an Ed25519 public key for a Convex account
 * 
 * AccountKey is basically the public key represented as a 256-bit blob (32 bytes)
 * 
 */
public class AccountKey extends AArrayBlob {
	public static final int LENGTH = Constants.KEY_LENGTH;

	public static final int LENGTH_BITS = LENGTH * 8;

	/**
	 * A null Account Key
	 */
	public static final AccountKey NULL = null;

	private AccountKey(byte[] data, int offset, int length) {
		super(data, offset, length);
		this.memorySize=0;
		if (length != LENGTH) throw new IllegalArgumentException("AccountKey length must be " + LENGTH + " bytes");
	}
	
	@Override
	@SuppressWarnings("unchecked")
	protected <R extends ACell> Ref<R> createRef() {
		// Create Ref at maximum status to reflect internal embedded status
		Ref<ACell> newRef= RefDirect.create(this,cachedHash(),Ref.VALID_EMBEDDED_FLAGS);
		cachedRef=newRef;
		return (Ref<R>) newRef;
	}

	/**
	 * Wraps the specified bytes as an AccountKey object. Warning: underlying bytes are
	 * used directly. Use only if no external references to the byte array will be
	 * retained.
	 * 
	 * @param data Byte array to wrap as Account Key
	 * @return An Address wrapping the given bytes
	 */
	public static AccountKey wrap(byte[] data) {
		return new AccountKey(data, 0, data.length);
	}

	/**
	 * Wraps the specified bytes as an AccountKey object. Warning: underlying bytes are
	 * used directly. Use only if no external references to the byte array will be
	 * retained.
	 * 
	 * @param data   Data array containing address bytes.
	 * @param offset Offset into byte array
	 * @return An Address wrapping the given bytes
	 */
	public static AccountKey wrap(byte[] data, int offset) {
		return new AccountKey(data, offset, LENGTH);
	}
	
	/**
	 * Creates an AccountKey from a blob. Must have correct length.
	 * @param b Blob to wrap as Account Key
	 * @return AccountKey instance, or null if not valid
	 */
	public static AccountKey create(ABlob b) {
		if (b==null) return null;
		if (b.count()!=LENGTH) return null;
		if (b instanceof AccountKey) return (AccountKey) b;
		if (b instanceof AArrayBlob) {
			AArrayBlob ab=(AArrayBlob)b;
			return new AccountKey(ab.getInternalArray(),ab.getInternalOffset(),LENGTH);
		}
		return wrap(b.getBytes());
	}

	/**
	 * Creates a "Dummy" Address that is not a valid public key, and therefore
	 * cannot have valid signed transactions.
	 * 
	 * To do this, a short hex nonce is repeated to fill the entire address length. This
	 * construction makes it possible to examine an Address and assess whether it is (plausibly) 
	 * a dummy address.
	 * 
	 * @param nonce Hex string to repeat to produce a visible dummy address
	 * @return An Address that cannot be used to sign transactions.
	 */
	public static AccountKey dummy(String nonce) {
		int n = nonce.length();
		if (n == 0) nonce="FFFF0000";
		if (n >= LENGTH / 2) throw new IllegalArgumentException("Nonce too long for dummy address");
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < LENGTH * 2; i += n) {
			sb.append(nonce);
		}
		return AccountKey.fromHex(sb.substring(0, LENGTH * 2));
	}

	public boolean equals(AccountKey o) {
		if (o == this) return true;
		return Utils.arrayEquals(o.store, o.offset, this.store, this.offset, LENGTH);
	}

	/**
	 * Constructs an AccountKey object from a hex string.
	 * Throws an exception if string is not valid
	 * 
	 * @param hexString Hex String
	 * @return An AccountKey constructed from the hex string
	 */
	public static AccountKey fromHex(String hexString) {
		AccountKey result = fromHexOrNull(hexString);
		if (result == null) throw new IllegalArgumentException("Invalid Address hex String [" + hexString + "]");
		return result;
	}
	
	/**
	 * Attempts to parse an account key on best efforts basis.
	 * 
	 * @param o Any object expected to represent an Account Key
	 * @return AccountKey instance, or null if not possible to parse
	 */
	public static AccountKey parse(Object o) {
		if (o instanceof ACell) {
			if (o instanceof ABlob) return create((ABlob)o);
			o=RT.jvm((ACell)o);
		}
		if (o instanceof String) {
			return parse((String)o);
		}
		return null;
	}
	
	/**
	 * Attempts to parse account key. Handles leading/trailing whitespace and optional 0x
	 * @param s String containing account key
	 * @return AccountKey, or null if not possible to parse
	 */
	public static AccountKey parse(String s) {
		if (s==null) return null;
		s=s.trim();
		if (s.startsWith("0x")) s=s.substring(2);
		return fromHexOrNull(s);
	}

	/**
	 * Constructs an AccountKey object from a hex string
	 * 
	 * @param hexString Hex String
	 * @return An AccountKey constructed from the hex string, or null if not a valid
	 *         hex string
	 */
	public static AccountKey fromHexOrNull(String hexString) {
		byte[] bs = Utils.hexToBytes(hexString, LENGTH * 2);
		if (bs == null) return null; // invalid string
		if (bs.length != LENGTH) return null; // wrong length
		return wrap(bs);
	}
	
	/**
	 * Constructs an AccountKey object from a hex string
	 * 
	 * @param hexString Hex String
	 * @return An AccountKey constructed from the hex string, or null if not a valid
	 *         hex string
	 */
	public static AccountKey fromHexOrNull(AString hexString) {
		if (hexString.count()!=LENGTH*2) return null;
		return fromHexOrNull(hexString.toString());
	}


	/**
	 * Constructs an AccountKey object from a checksummed hex string.
	 * 
	 * Throws an exception if checksum is not valid
	 * 
	 * @param hexString Hex String
	 * @return An AccountKey constructed from the hex string
	 */
	public static AccountKey fromChecksumHex(String hexString) {
		byte[] bs = Utils.hexToBytes(hexString, LENGTH * 2);
		Hash h = Blob.wrap(bs).getContentHash();
		for (int i = 0; i < LENGTH * 2; i++) {
			int dh = h.getHexDigit(i);
			char c = hexString.charAt(i);
			if (Character.isDigit(c)) continue;
			boolean check = (c >= 'a') ^ (dh >= 8); // note 'a' is higher than 'A'
			if (!check)
				throw new IllegalArgumentException("Bad checksum at position " + i + " in address " + hexString);
		}
		return AccountKey.wrap(bs);
	}

	/**
	 * Converts this AccountKey to a checksummed hex string.
	 * 
	 * @return A String containing the checksummed hex representation of this
	 *         Address
	 */
	public String toChecksumHex() {
		StringBuilder sb = new StringBuilder(64);
		Hash h = this.toFlatBlob().getContentHash();
		for (int i = 0; i < LENGTH * 2; i++) {
			int dh = h.getHexDigit(i);
			int da = this.getHexDigit(i);
			if (da < 10) {
				sb.append((char) ('0' + da));
			} else {
				boolean up = (dh >= 8);
				sb.append((char) ((up ? 'A' : 'a') + da - 10));
			}
		}
		return sb.toString();
	}

	public static AccountKey readRaw(ByteBuffer data) {
		byte[] buff = new byte[LENGTH];
		data.get(buff);
		return AccountKey.wrap(buff);
	}
	
	public static AccountKey readRaw(Blob b, int pos) {
		byte[] data=b.getInternalArray();
		int off=b.getInternalOffset(); // offset of account key in source blob
		if (pos+LENGTH>b.count()) throw new IndexOutOfBoundsException("wrapping AccountKey beyond bound");
		return AccountKey.wrap(data,off+pos);
	}

	@Override
	public int estimatedEncodingSize() {
		// tag plus LENGTH bytes
		return 3 + LENGTH;
	}
	
	@Override
	public int getEncodingLength() {
		// Always a fixed encoding length, tag plus count plus length
		return 2 + LENGTH;
	}

	@Override
	public Blob getChunk(long i) {
		if (i != 0) throw new IndexOutOfBoundsException(Errors.badIndex(i));
		return toFlatBlob();
	}

	@Override
	public void validateCell() throws InvalidDataException {
		if (count != LENGTH)
			throw new InvalidDataException("Address length must be " + LENGTH + "  bytes = " + LENGTH_BITS + " bits",
					this);
	}

	@Override
	public boolean isEmbedded() {
		return true;
	}
	
	@Override
	protected long calcMemorySize() {	
		// always embedded and no child Refs, so memory size == 0
		return 0;
	}

	@Override
	public boolean isCanonical() {
		return false;
	}

	@Override
	protected Blob toCanonical() {
		return toFlatBlob();
	}

}
