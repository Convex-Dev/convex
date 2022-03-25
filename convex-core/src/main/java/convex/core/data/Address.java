package convex.core.data;

import java.nio.ByteBuffer;
import java.security.MessageDigest;

import convex.core.data.type.AType;
import convex.core.data.type.Types;
import convex.core.exceptions.BadFormatException;
import convex.core.exceptions.InvalidDataException;
import convex.core.util.Utils;

/**
 * Immutable class representing an Address, generally used to uniquely identify an Account.
 * 
 * An Address is a specialised 8-byte long blob instance that wraps a non-negative long Account number. This number
 * serves as an index into the vector of accounts for the current state.
 * 
 */
public final class Address extends ALongBlob {


	public static final Address ZERO = Address.create(0);

	private Address(long value) {
		super(value);
	}
	
	/**
	 * Creates an Address from a blob. Number be a valid non-negative long value.
	 * 
	 * @param number Account number
	 * @return Address instance, or null if not valid
	 */
	public static Address create(long number) {
		if (number<0) return null;
		return new Address(number);
	}

	/**
	 * Creates an Address from a blob. Must be a valid long value
	 * @param b Blob to convert to an Address
	 * @return Address instance, or null if not valid
	 */
	public static Address create(ABlob b) {
		if (b.count()!=8) return null;
		return create(b.longValue());
	}
	
	@Override
	public AType getType() {
		return Types.ADDRESS;
	}
	

	@Override
	public int hashCode() {
		// note: We use the Java hashcode of a long 
		return Long.hashCode(value);
	}

	@Override
	public boolean equals(ABlob o) {
		if (!(o instanceof Address)) return false;
		return value==((Address) o).value;
	}

	public boolean equals(Address o) {
		return value==o.value;
	}

	/**
	 * Constructs an Address object from a hex string
	 * 
	 * @param hexString String to read Address from
	 * @return An Address constructed from the hex string, or null if not a valid
	 *         hex string
	 */
	public static Address fromHex(String hexString) {
		// catch nulls just in case
		if (hexString==null) return null;
	
		// catch odd length
		if ((hexString.length()&1)!=0) return null;
		
		if (hexString.length()>16) return null;
		Blob b=Blob.fromHex(hexString);
		if (b==null) return null;
		if (b.length!=8) return null;
		return create(b.longValue());
	}
	
	/**
	 * Constructs an Address from an arbitrary String, attempting to parse different possible formats
	 * @param s String to parse
	 * @return Address parsed, or null if not valid
	 */
	public static Address parse(String s) {
		s=s.trim();
		if (s.startsWith("#")) {
			s=s.substring(1);
		} 
		
		if (s.startsWith("0x")) {
			s=s.substring(2);
			return fromHex(s);
		}
		
		try {
			long l=Long.parseLong(s);
			return Address.create(l);
		} catch (NumberFormatException e) {
			// fall through
		}
		
		return null;
	}

	public static Address readRaw(ByteBuffer bb) throws BadFormatException {
		long value=Format.readVLCLong(bb);
		Address a= Address.create(value);
		if (a==null) throw new BadFormatException("Invalid Address: "+value);
		return a;
	}

	@Override
	public int encode(byte[] bs, int pos) {
		bs[pos++]=Tag.ADDRESS;
		return encodeRaw(bs,pos);
	}
	
	@Override
	public boolean print(BlobBuilder sb, long limit) {
		sb.append("#");
		sb.append(Long.toString(value));
		return sb.check(limit);
	}
	
	@Override
	public AString toCVMString(long limit) {
		if (limit<2) return null;
		return Strings.create("#"+value);
	}

	@Override
	public boolean isCanonical() {
		// always canonical, since class invariants are maintained
		return true;
	}

	@Override
	public int estimatedEncodingSize() {
		// tag plus LENGTH bytes
		return 1 + Format.MAX_VLC_LONG_LENGTH;
	}

	@Override
	public void validateCell() throws InvalidDataException {
		if (value<0)
			throw new InvalidDataException("Address must be positive",this);
	}

	@Override
	public boolean isRegularBlob() {
		return false;
	}

	@Override
	public void getBytes(byte[] dest, int destOffset) {
		Utils.writeLong(dest, destOffset, value);
	}

	@Override
	public Blob slice(long start, long length) {
		return toFlatBlob().slice(start,length);
	}

	@Override
	public Blob toFlatBlob() {
		byte[] bs=new byte[8];
		Utils.writeLong(bs, 0, value);
		return Blob.wrap(bs);
	}

	@Override
	protected void updateDigest(MessageDigest digest) {
		toFlatBlob().updateDigest(digest);
	}
	
	@Override
	public boolean equalsBytes(byte[] bytes, int byteOffset) {
		return value==Utils.readLong(bytes, byteOffset);
	}

	@Override
	public long longValue() {
		return value;
	}

	@Override
	public int encodeRaw(byte[] bs, int pos) {
		return Format.writeVLCLong(bs, pos, value);
	}
	
	public static final int MAX_ENCODING_LENGTH = 1+Format.MAX_VLC_LONG_LENGTH;

	@Override
	public byte getTag() {
		return Tag.ADDRESS;
	}

	@Override
	public Address toCanonical() {
		return this;
	}

	/**
	 * Creates a new Address at an offset to this Address
	 * @param offset Offset to add to this Address (may be negative)
	 * @return New Address
	 */
	public Address offset(long offset) {
		return create(value+offset);
	}




}
