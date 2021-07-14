package convex.core.data;

import java.nio.ByteBuffer;

import convex.core.Constants;
import convex.core.data.type.AType;
import convex.core.data.type.Types;
import convex.core.exceptions.InvalidDataException;
import convex.core.util.Errors;
import convex.core.util.Utils;

/**
 * Immutable class representing an Ed25519 Public Key for an Account
 * 
 * <p>
 * Using Ed25519:
 * </p>
 * <li>AccountKey is the Public Key (32 bytes)</li>
 * 
 */
public class AccountKey extends AArrayBlob {
	public static final int LENGTH = Constants.KEY_LENGTH;
	
	public static final AType TYPE = Types.BLOB;


	public static final int LENGTH_BITS = LENGTH * 8;

	public static final AccountKey ZERO = AccountKey.dummy("0");

	private AccountKey(byte[] data, int offset, int length) {
		super(data, offset, length);
		if (length != LENGTH) throw new IllegalArgumentException("AccountKey length must be " + LENGTH + " bytes");
	}
	
	@Override
	public AType getType() {
		return TYPE;
	}

	/**
	 * Wraps the specified bytes as an AccountKey object. Warning: underlying bytes are
	 * used directly. Use only if no external references to the byte array will be
	 * retained.
	 * 
	 * @param data
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
	 * @param b
	 * @return AccountKey insatnce, or null if not valid
	 */
	public static AccountKey create(ABlob b) {
		if (b.count()!=LENGTH) return null;
		if (b instanceof AccountKey) return (AccountKey) b;
		if (b instanceof AArrayBlob) {
			AArrayBlob ab=(AArrayBlob)b;
			return new AccountKey(ab.getInternalArray(),ab.getOffset(),LENGTH);
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
		if (n == 0) throw new Error("Empty nonce");
		if (n >= LENGTH / 2) throw new Error("Nonce too long for dummy address");
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < LENGTH * 2; i += n) {
			sb.append(nonce);
		}
		return AccountKey.fromHex(sb.substring(0, LENGTH * 2));
	}

	@Override
	public boolean equals(ABlob o) {
		if (o==null) return false;
		if (o instanceof AccountKey) return equals((AccountKey)o);
		if (o.getType()!=TYPE) return false;
		if (o.count()!=LENGTH) return false;
		return o.equalsBytes(this.store, this.offset);
	}

	public boolean equals(AccountKey o) {
		if (o == this) return true;
		return Utils.arrayEquals(o.store, o.offset, this.store, this.offset, LENGTH);
	}

	/**
	 * Constructs an AccountKey object from a hex string
	 * 
	 * @param hexString
	 * @return An AccountKey constructed from the hex string, or null if not a valid
	 *         hex string
	 */
	public static AccountKey fromHex(String hexString) {
		AccountKey result = fromHexOrNull(hexString);
		if (result == null) throw new Error("Invalid Address hex String [" + hexString + "]");
		return result;
	}

	/**
	 * Constructs an AccountKey object from a hex string
	 * 
	 * @param hexString
	 * @return An Address constructed from the hex string, or null if not a valid
	 *         hex string
	 */
	public static AccountKey fromHexOrNull(String hexString) {
		byte[] bs = Utils.hexToBytes(hexString, LENGTH * 2);
		if (bs == null) return null; // invalid string
		if (bs.length != LENGTH) return null; // wrong length
		return wrap(bs);
	}
	
	public static AccountKey fromHexOrNull(AString a) {
		if (a.length()!=LENGTH*2) return null;
		return fromHexOrNull(a.toString());
	}


	/**
	 * Constructs an AccountKey object from a checksummed hex string.
	 * 
	 * Throws an exception if checksum is not valid
	 * 
	 * @param hexString
	 * @return An Address constructed from the hex string
	 */
	public static AccountKey fromChecksumHex(String hexString) {
		byte[] bs = Utils.hexToBytes(hexString, LENGTH * 2);
		AccountKey a = AccountKey.wrap(bs);
		Hash h = a.getContentHash();
		for (int i = 0; i < LENGTH * 2; i++) {
			int dh = h.getHexDigit(i);
			char c = hexString.charAt(i);
			if (Character.isDigit(c)) continue;
			boolean check = (c >= 'a') ^ (dh >= 8); // note 'a' is higher than 'A'
			if (!check)
				throw new IllegalArgumentException("Bad checksum at position " + i + " in address " + hexString);
		}
		return a;
	}

	/**
	 * Converts this AccountKey to a checksummed hex string.
	 * 
	 * @return A String containing the checksummed hex representation of this
	 *         Address
	 */
	public String toChecksumHex() {
		StringBuilder sb = new StringBuilder(64);
		Hash h = this.getContentHash();
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

	@Override
	public int encode(byte[] bs, int pos) {
		bs[pos++]=Tag.BLOB;
		bs[pos++]=Constants.KEY_LENGTH;
		return encodeRaw(bs,pos);
	}
	
	@Override public final boolean isCVMValue() {
		return true;
	}

	@Override
	public int estimatedEncodingSize() {
		// tag plus LENGTH bytes
		return 1 + LENGTH;
	}

	@Override
	public Blob getChunk(long i) {
		if (i != 0) throw new IndexOutOfBoundsException(Errors.badIndex(i));
		return toBlob();
	}

	@Override
	public void validateCell() throws InvalidDataException {
		if (length != LENGTH)
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
	public boolean isRegularBlob() {
		return true;
	}

	@Override
	public byte getTag() {
		return Tag.BLOB;
	}

	@Override
	public boolean isCanonical() {
		return false;
	}

	@Override
	public Blob toCanonical() {
		return toBlob();
	}



}
