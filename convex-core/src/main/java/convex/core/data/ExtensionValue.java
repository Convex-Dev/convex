package convex.core.data;

import convex.core.data.util.BlobBuilder;
import convex.core.exceptions.InvalidDataException;
import convex.core.lang.RT;
import convex.core.util.Bits;
import convex.core.util.Utils;

public class ExtensionValue extends AExtensionValue {

	protected final byte tag;
	protected final long value;
	
	protected ExtensionValue(byte tag,long value) {
		this.tag=tag;
		this.value=value;
	}
	
	/**
	 * Create a CAD3 extension value
	 * @return Extension value, or null if not valid
	 */
	public static ExtensionValue create(byte tag, long value) {
		if (value<0) return null;
		if ((byte)(tag&0xF0)!=Tag.EXTENSION_VALUE_BASE) return null; // not an extension value
				
		return new ExtensionValue(tag,value);
	}
	
	@Override
	public void validateCell() throws InvalidDataException {
		if ((byte)(this.tag&0xF0)!=Tag.EXTENSION_VALUE_BASE) {
			throw new InvalidDataException("Invalide Code tag: 0x"+Utils.toHexString(tag),this);
		}
		if (this.value<0) {
			throw new InvalidDataException("Negaitive code value",this);
		}
	}
	
	@Override
	public int hashCode() {
		return Bits.hash32(value);
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
	public long longValue() {
		return value;
	}

	@Override
	public byte getTag() {
		return tag;
	}
	
	@Override
	public boolean isCanonical() {
		// Always canonical if valid
		return value>=0;
	}

	@Override
	public int encode(byte[] bs, int pos) {
		bs[pos++]=tag;
		return encodeRaw(bs,pos);
	}

	@Override
	public int encodeRaw(byte[] bs, int pos) {
		pos=Format.writeVLQCount(bs, pos, value);
		return pos;
	}


	@Override
	public boolean isCVMValue() {
		return false;
	}

	@Override
	public boolean print(BlobBuilder sb, long limit) {
		return RT.printCAD3(sb,limit,this);
	}





}
