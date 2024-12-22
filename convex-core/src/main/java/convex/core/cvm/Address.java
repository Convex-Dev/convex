package convex.core.cvm;

import convex.core.data.ABlob;
import convex.core.data.ABlobLike;
import convex.core.data.ACell;
import convex.core.data.AExtensionValue;
import convex.core.data.AString;
import convex.core.data.Blob;
import convex.core.data.Cells;
import convex.core.data.Format;
import convex.core.data.Strings;
import convex.core.data.prim.CVMLong;
import convex.core.data.type.AType;
import convex.core.data.type.Types;
import convex.core.data.util.BlobBuilder;
import convex.core.exceptions.BadFormatException;
import convex.core.exceptions.InvalidDataException;
import convex.core.lang.RT;
import convex.core.util.Utils;

/**
 * Immutable class representing an Address, generally used to uniquely identify an Account.
 * 
 * An Address is a specialised 8-byte long blob instance that wraps a non-negative long Account number. This number
 * serves as an index into the vector of accounts for the current state.
 * 
 */
public final class Address extends AExtensionValue {

	public static final int LENGTH=8;
	
	private static final int CACHE_SIZE=256;
	private static Address[] CACHE=new Address[CACHE_SIZE];
	static {
		for (int i=0; i<CACHE_SIZE; i++) {
			CACHE[i]=new Address(i);
		}
	}
	
	/**
	 * The Zero Address
	 */
	public static final Address ZERO = CACHE[0];
	
	/**
	 * The maximum possible Address
	 */
	public static final Address MAX_VALUE = Address.create(Long.MAX_VALUE);

	private Address(long value) {
		super(value);
	}
	
	/**
	 * Obtains an Address. Number must be a valid non-negative long value.
	 * 
	 * @param number Account number
	 * @return Address instance, or null if not valid
	 */
	public static Address create(long number) {
		if (number<CACHE_SIZE) {
			if (number<0) return null;
			return CACHE[(int)number];
		}
		return new Address(number);
	}
	
	/**
	 * Creates an Address without checking.
	 * 
	 * @param number Account number
	 * @return Address instance, may be invalid
	 */
	public static Address unsafeCreate(long number) {
		return new Address(number);
	}

	/**
	 * Obtains an Address from a blob. Must be a valid long value
	 * @param b Blob to convert to an Address
	 * @return Address instance, or null if not valid
	 */
	public static Address create(ABlobLike<?> b) {
		if (b.count()>BYTE_LENGTH) return null;
		return create(b.longValue());
	}
	
	@Override
	public AType getType() {
		return Types.ADDRESS;
	}

	@Override
	public boolean equals(ACell o) {
		if (o==this) return true;
		if (o instanceof Address) {
			return value==((Address) o).value;
		} else {
			return Cells.equalsGeneric(this, o);
		}
	}

	public final boolean equals(Address o) {
		if (o==null) return false;
		return value==o.value;
	}

	/**
	 * Obtains an Address from a hex string
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
		return create(b.longValue());
	}
	
	/**
	 * Obtains an Address from an arbitrary String, attempting to parse possible formats '123' '0xc3' or '#123'
	 * @param s String to parse
	 * @return Address parsed, or null if not valid
	 */
	public static Address parse(String s) {
		if (s==null) return null;
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
			return null;
		}
	}
	
	/**
	 * Attempts to parse an address from an arbitrary object. Accepts strings and numbers on a best efforts basis.
	 * @param o Object to parse as an Address
	 * @return Address parsed, or null if not valid
	 */
	public static Address parse(Object o) {
		if (o==null) return null;
		if (o instanceof ACell) {
			Address add=RT.castAddress((ACell)o);
			if (add!=null) return add;
			o=RT.jvm((ACell)o); // convert to JVM type
		}
		if (o instanceof String) return parse((String)o);
		if (o instanceof Number) {
			Number n=(Number)o;
			long l=n.longValue();
			if (l==n.doubleValue()) return Address.create(l);
		}
		return null;
	}
	
	public static Address read(Blob b, int pos) throws BadFormatException {
		long value=Format.readVLQCount(b,pos+1); // skip tag, we assume correct
		Address a= Address.create(value);
		if (a==null) throw new BadFormatException("Invalid Address: "+value);
		int epos=pos+1+Format.getVLQCountLength(value);
		a.attachEncoding(b.slice(pos, epos));
		return a;
	}

	@Override
	public int encode(byte[] bs, int pos) {
		bs[pos++]=CVMTag.ADDRESS;
		return encodeRaw(bs,pos);
	}
	
	@Override
	public int encodeRaw(byte[] bs, int pos) {
		return Format.writeVLQCount(bs, pos, value);
	}
	
	@Override
	public boolean print(BlobBuilder sb, long limit) {
		sb.append("#");
		sb.appendLongString(value);
		return sb.check(limit);
	}
	
	@Override
	public AString toCVMString(long limit) {
		if (limit<2) return null;
		return Strings.create(toString());
	}
	
	@Override
	public String toString() {
		return "#"+value;
	}

	@Override
	public void validateCell() throws InvalidDataException {
		if (value<0) throw new InvalidDataException("Address must be positive",this);
	}

	@Override
	public Blob toFlatBlob() {
		byte[] bs=new byte[BYTE_LENGTH];
		Utils.writeLong(bs, 0, value);
		return Blob.wrap(bs);
	}
	
	public static final int MAX_ENCODING_LENGTH = 1+Format.MAX_VLQ_COUNT_LENGTH;

	@Override
	public byte getTag() {
		return CVMTag.ADDRESS;
	}

	@Override
	public Address toCanonical() {
		return this;
	}

	/**
	 * Creates a new Address at an offset to this Address
	 * @param offset Offset to add to this Address (may be negative)
	 * @return New Address, or null if would be invalid
	 */
	public Address offset(long offset) {
		return create(value+offset);
	}

	@Override
	public final byte byteAt(long i) {
		checkIndex(i);
		return (byte) Utils.longByteAt(value,i);
	}
	
	@Override
	public final byte byteAtUnchecked(long i) {
		return (byte) Utils.longByteAt(value,i);
	}
	
	@Override
	public final int getBytes(byte[] bs, int pos) {
		pos=Utils.writeLong(bs, pos, value);
		return pos;
	}

	@Override
	public boolean equalsBytes(ABlob b) {
		if (b.count()!=LENGTH) return false;
		return b.longValue()==(value);
	}

	@Override
	protected int compareTo(long bvalue) {
		return Long.compareUnsigned(value, bvalue);
	}

	@Override
	public boolean isCVMValue() {
		return true;
	}

	@Override
	public CVMLong get(long i) {
		checkIndex(i);
		return CVMLong.create(Utils.longByteAt(value,i));
	}

	@Override
	public boolean isCanonical() {
		// Always canonical, we assume valid by construction
		return true;
	}
}
