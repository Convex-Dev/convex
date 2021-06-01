package convex.core.data;

import java.nio.ByteBuffer;
import java.security.MessageDigest;

import convex.core.exceptions.BadFormatException;
import convex.core.exceptions.InvalidDataException;
import convex.core.util.Errors;
import convex.core.util.Utils;

/**
 * Immutable class representing an Address.
 * 
 * An Address is a specialised 8-byte long blob instance that wraps a non-negative long account number. This number
 * serves as an index into the vector of accounts for the current state.
 * 
 */
public class Address extends ABlob {

	private final long value;

	public static final Address ZERO = Address.create(0);

	private Address(long value) {
		this.value=value;
	}
	
	/**
	 * Creates an Address from a blob. Must be a valid long value
	 * @param b
	 * @return Address instance, or null if not valid
	 */
	public static Address create(long value) {
		if (value<0) return null;
		return new Address(value);
	}

	/**
	 * Creates an Address from a blob. Must be a valid long value
	 * @param b
	 * @return Address instance, or null if not valid
	 */
	public static Address create(ABlob b) {
		if (b.count()!=8) return null;
		return create(b.longValue());
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
	 * @param hexString
	 * @return An Address constructed from the hex string, or null if not a valid
	 *         hex string
	 */
	public static Address fromHex(String hexString) {
		// catch nulls just in case
		if (hexString==null) return null;
	
		// catch odd length
		if ((hexString.length()&1)!=0) return null;
		
		Address result = fromHexOrNull(hexString);
		return result;
	}

	/**
	 * Constructs an Address object from a hex string
	 * 
	 * @param hexString
	 * @return An Address constructed from the hex string, or null if not a valid
	 *         hex string
	 */
	public static Address fromHexOrNull(String hexString) {
		if (hexString.length()>16) return null;
		Blob b=Blob.fromHex(hexString);
		if (b==null) return null;
		if (b.length!=8) return null;
		return create(b.longValue());
	}
	
	/**
	 * Constructs an Address from an arbitrary String, attempting to parse different possible formats
	 * @param bb
	 * @return Address parsed, or null if not valid
	 */
	public static Address parse(String s) {
		s=s.trim();
		if (s.startsWith("#")) {
			s=s.substring(1);
		} 
		
		if (s.startsWith("0x")) {
			s=s.substring(2);
			return fromHexOrNull(s);
		}
		
		try {
			Long l=Long.parseLong(s);
			if (l!=null) return Address.create(l);
		} catch (NumberFormatException e) {
			// fall through
		}
		
		return null;
	}
	
	/**
	 * Constructs an Address from an arbitrary Object, attempting to parse different possible formats
	 * @param bb
	 * @return Address parsed, or null if not valid
	 */
	public static Address parse(Object o) {
		if (o instanceof Address) return (Address) o;
		if (o instanceof String) return parse((String)o);
		if (o instanceof Number) return create(((Number)o).longValue());
		if (o instanceof ABlob) return create((ABlob)o);
		return null;
	}

	public static Address readRaw(ByteBuffer bb) throws BadFormatException {
		long value=Format.readVLCLong(bb);
		Address a= Address.create(value);
		if (a==null) throw new BadFormatException("Invalid VLC encoding for Address");
		return a;
	}

	@Override
	public int encode(byte[] bs, int pos) {
		bs[pos++]=Tag.ADDRESS;
		return encodeRaw(bs,pos);
	}

	@Override
	public void ednString(StringBuilder sb) {
		sb.append("#addr ");
		sb.append(value);
	}
	
	@Override
	public void print(StringBuilder sb) {
		sb.append("#");
		sb.append(value);
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
	public Blob getChunk(long i) {
		if (i != 0) throw new IndexOutOfBoundsException(Errors.badIndex(i));
		return toBlob();
	}

	@Override
	public void validateCell() throws InvalidDataException {
		if (value<0)
			throw new InvalidDataException("Address must be positive",this);

	}

	@Override
	public boolean isEmbedded() {
		return true;
	}
	
	@Override public final boolean isCVMValue() {
		return true;
	}
	
	@Override
	protected long calcMemorySize() {	
		// always embedded and no child Refs, so memory size == 0
		return 0;
	}

	@Override
	public boolean isRegularBlob() {
		return false;
	}

	@Override
	public void getBytes(byte[] dest, int destOffset) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public long count() {
		return 8;
	}


	@Override
	public String toHexString() {
		return Utils.toHexString(value);
	}

	@Override
	public ABlob slice(long start, long length) {
		if ((start==0)&&(length==8)) return this;
		return toBlob().slice(start,length);
	}

	@Override
	public Blob toBlob() {
		byte[] bs=new byte[8];
		Utils.writeLong(bs, 0, value);
		return Blob.wrap(bs);
	}

	@Override
	public long commonHexPrefixLength(ABlob b) {
		return toBlob().commonHexPrefixLength(b);
	}

	@Override
	protected void updateDigest(MessageDigest digest) {
		toBlob().updateDigest(digest);
	}

	@Override
	public byte getUnchecked(long i) {
		return (byte)(value>>((7-i)*8));
	}

	@Override
	public ABlob append(ABlob b) {
		return toBlob().append(b);
	}

	@Override
	public ByteBuffer writeToBuffer(ByteBuffer bb) {
		return toBlob().writeToBuffer(bb);
	}

	@Override
	public int writeToBuffer(byte[] bs, int pos) {
		return toBlob().writeToBuffer(bs,pos);
	}

	@Override
	public ByteBuffer getByteBuffer() {
		return toBlob().getByteBuffer();
	}

	@Override
	public void toHexString(StringBuilder sb) {
		String s= Utils.toHexString(value);
		sb.append(s);
	}

	@Override
	public long hexMatchLength(ABlob b, long start, long length) {
		return toBlob().hexMatchLength(b,start,length);
	}
	
	@Override
	public boolean equalsBytes(byte[] bytes, int byteOffset) {
		return value==Utils.readLong(bytes, byteOffset);
	}

	@Override
	public long toLong() {
		return value;
	}

	@Override
	public long longValue() {
		return value;
	}

	@Override
	public int encodeRaw(byte[] bs, int pos) {
		return Format.writeVLCLong(bs, pos, value);
	}

	@Override
	public int getRefCount() {
		return 0;
	}

	@Override
	public byte getTag() {
		return Tag.ADDRESS;
	}


}
