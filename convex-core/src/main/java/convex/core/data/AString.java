package convex.core.data;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CoderResult;

import convex.core.data.prim.CVMChar;
import convex.core.data.type.AType;
import convex.core.data.type.Types;
import convex.core.util.Utils;

/**
 * Class representing a CVM String
 */
public abstract class AString extends ACountable<CVMChar> implements Comparable<AString> {

	
	protected long length;
	
	protected AString(long length) {
		this.length=length;
	}
	
	@Override
	public AType getType() {
		return Types.STRING;
	}

	@Override
	public boolean print(BlobBuilder sb, long limit) {
		if (!sb.check(limit-count())) return false;
		sb.append('"');
		// TODO. Fix escaping. Can only increase length?
		sb.append(this);
		sb.append('"');
		return sb.check(limit);
	}
	
	@Override
	public long count() {
		return length;
	}
	
	public StringShort empty() {
		return Strings.EMPTY;
	}

	/**
	 * Gets the Unicode character at position i, or -1 if not valid
	 * @param i Index into String (byte position)
	 * @return Unicode code point, or -1 if not a valid code point at this position
	 */
	public abstract int charAt(long i);
	
	/**
	 * Gets 32 bytes integer at given position. Extends with 255 (invalid UTF-8) if needed
	 * @param index Index into String (byte position)
	 * @return Raw integer value
	 */
	public int intAt(long index) {
		int r=0;
		for (int i=0; i<4; i++) {
			r=(r>>8)+(0xff&byteAt(index+i));
		}
		return r;
	}
	
	/**
	 * Gets a byte at the specified index. Returns -1 (0xff) if outside String.
	 * @param i Index into String (byte position)
	 * @return Raw byte value
	 */
	public abstract byte byteAt(long i);
	
	@Override
	public CVMChar get(long i) {
		return CVMChar.create(charAt((int)i));
	}
	
	@Override
	public Ref<CVMChar> getElementRef(long i) {
		return get(i).getRef();
	}
	
	@Override
	public abstract int compareTo(AString o);
	
	@Override 
	public final String toString() {
		int n=Utils.checkedInt(count());
		ByteBuffer bb=ByteBuffer.allocate(n);
		writeToBuffer(bb);
		bb.flip(); // Prepare to read
		
		int cn=Math.min(4096, n); // Guess sensible size for CharBuffer
		CharBuffer cb=CharBuffer.allocate(cn);
		CharsetDecoder dec=Strings.getDecoder();
		StringBuilder sb=new StringBuilder(cn);
		
		while (bb.hasRemaining()) {
			CoderResult cr=dec.decode(bb, cb, false);
			cb.flip();
			sb.append(cb.toString());
			cb.clear();
			if (cr==CoderResult.UNDERFLOW) break;
		}
		dec.decode(bb,cb,true); // Mark end of input
		cb.flip();
		sb.append(cb.toString());
		cb.clear();
		
		return sb.toString();
	}
	
	@Override
	public AString toCVMString(long limit) {
		if (limit<count()) return null;
		return this;
	}
	
	/**
	 * Append a CVM String to this CVM String. Potentially O(n). Concatenates raw UTF-8 bytes.
	 * @param b String to append
	 * @return Concatenated String
	 */
	public AString append(AString b) {
		if (length==0) return b;
		long n=b.count(); 
		if (n==0) return this;
		return Strings.create(toBlob().append(b.toBlob()));
	}
	
	protected abstract void writeToBuffer(ByteBuffer bb);

	/**
	 *  Gets a subsequence of this String
	 * @param start Start index (inclusive)
	 * @param end End index (Exclusive)
	 * @return Specified substring
	 */
	public abstract AString subString(long start, long end);

	/**
	 * Splits this string by the given character
	 * 
	 * The result will always have at least one String, and as many additional Strings as the
	 * split character occurs.
	 * @param c CMVChar instance with which to split
	 * 
	 * @return Vector of Strings, excluding the split character.
	 */
	public AVector<AString> split(CVMChar c) {
		long start=0;
		AVector<AString> acc=Vectors.empty();
		long n=count();
		int cp=c.getCodePoint();
		int utfLength=CVMChar.utfLength(cp);
		for (int i=0; i<n;) {
			int ch=charAt(i);
			if (ch==cp) {
				acc=acc.append(subString(start,i));
				i+=utfLength;
				start=i; // update start point of next string
			} else {
				int inc=CVMChar.utfLength(cp);
				if (inc<0) inc=1; // move one byte for bad chars
				i+=inc;
			}
		}
		acc=acc.append(subString(start,n));
		return acc;
	}
	
	@Override
	public int encode(byte[] bs, int pos) {
		bs[pos++]=Tag.STRING;
		return encodeRaw(bs,pos);
	}
	
	/**
	 * Encode the data of this String. Assumes tag already written
	 */
	public abstract int encodeRaw(byte [] bs, int pos);
	
	/**
	 * Encode the raw UTF-8 data of this String. Assumes tag/length already written
	 * @param bs Byte array to encode to
	 * @param pos Position in target array to write to
	 * @return End position in array after encoding
	 */
	public abstract int encodeRawData(byte [] bs, int pos);
	
	@Override
	public final byte getTag() {
		return Tag.STRING;
	}
	
	/**
	 * Gets a Java hashCode for this CVM String. Use the hashcode of underlying Blob
	 */
	@Override
	public final int hashCode() {
		return toBlob().hashCode();
	}

	/**
	 * Converts this String to a Blob byte representation. Must round trip all values.
	 * @return Blob representation of UTF-8 String
	 */
	public abstract ABlob toBlob();

	/**
	 * Convenience method to add a Java String to a CVM String. Not particularly efficient.
	 * @param string String to append
	 * @return CVM String
	 */
	public AString append(String string) {
		return append(Strings.create(string));
	}
}
