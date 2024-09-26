package convex.core.data;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CoderResult;

import convex.core.data.prim.CVMChar;
import convex.core.data.type.AType;
import convex.core.data.type.Types;
import convex.core.data.util.BlobBuilder;
import convex.core.util.Utils;

/**
 * Abstract base Class representing a CVM String. 
 * 
 * CVM Strings are UTF-8 byte strings with an immutable, fixed count in bytes. 
 * 
 * CVM Strings are NOT enforced to be valid UTF-8, for reasons of performance, simplicity and
 * consistent behaviour (e.g. in conversions to and from Blobs). It is up to clients to decide 
 * how to represent invalid UTF-8 if necessary.
 */
public abstract class AString extends ABlobLike<CVMChar> {

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
		long n=count();
		if (!sb.check(limit-(n+2))) {
			// Can't print full string, but attempt up to limit
			long avail=limit-sb.count();
			if (avail>0) {
				sb.append('"');
				printEscaped(sb,0,avail-1);
			}
			return false;
		}
		sb.append('"');
		printEscaped(sb,0,n);
		sb.append('"');
		return sb.check(limit);
	}
	
	@Override
	public long count() {
		return length;
	}
	
	/**
	 * Prints this string as escaped UTF-8
	 * @param sb
	 */
	protected abstract void printEscaped(BlobBuilder sb, long start, long end);
	
	/**
	 * Returns the singleton empty String
	 */
	@Override
	public final StringShort empty() {
		return StringShort.EMPTY;
	}

	/**
	 * Gets the Unicode character at position i, or -1 if not valid
	 * @param i Index into String (byte position)
	 * @return Unicode code point, or -1 if not a valid code point at this position
	 */
	public final int charAt(long i) {
		int utf=intAt(i);
		int cp=CVMChar.codepointFromUTFInt(utf);
		return cp;
	}
	
	/**
	 * Gets 32 bytes integer at given position. Extends with 255 (invalid UTF-8) if needed. The
	 * main purpose of this function is to enable fast peeking at UTF-8 characters
	 * 
	 * @param index Index into String (byte position)
	 * @return Raw integer value
	 */
	public int intAt(long index) {
		int r=0;
		for (int i=0; i<4; i++) {
			r|=(0xff&byteAt(index+i))<<(8*(3-i));
		}
		return r;
	}
	
	/**
	 * Gets a byte at the specified index. Returns -1 (0xff) if outside String.
	 * @param i Index into String (byte position)
	 * @return Raw byte value
	 */
	@Override
	public abstract byte byteAt(long i);
	
	/**
	 * Gets the Character at the specified point in the String, or null 
	 * if there is no valid Character at this position.
	 * 
	 * @return CVMChar instance, or null for invalid UTF-8 or any character out of the string bounds
	 */
	@Override
	public CVMChar get(long i) {
		return CVMChar.create(charAt((int)i));
	}
	
	@Override
	public Ref<CVMChar> getElementRef(long i) {
		return get(i).getRef();
	}
	
	@Override
	public int getBytes(byte[] dest, int destOffset) {
		return toBlob().getBytes(dest, destOffset);
	}
	
	@Override 
	public String toString() {
		int n=Utils.checkedInt(count());
		ByteBuffer bb=toBlob().toByteBuffer();
		
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
	public long hexMatch(ABlobLike<?> b, long start, long length) {
		return toBlob().hexMatch(b, start, length);
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

	/**
	 * Gets a slice of this string, or null if not a valid slice
	 * @param start Start index (inclusive)
	 * @param end End index (Exclusive)
	 * @return Specified substring
	 */
	public abstract AString slice(long start, long end);

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
		final long n=count();
		int cp=c.getCodePoint();
		int utfLength=CVMChar.utfLength(cp);
		for (int pos=0; pos<n;) {
			int ch=charAt(pos);
			if (ch==cp) {
				acc=acc.append(slice(start,pos));
				pos+=utfLength;
				start=pos; // update start point of next string
			} else {
				int inc=CVMChar.utfLength(ch);
				if (inc<0) inc=1; // move one byte for bad chars
				pos+=inc;
			}
		}
		acc=acc.append(slice(start,n));
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
	 * Write the raw UTF-8 data of this String to a byte array.
	 * @param bs Destination byte array
	 * @param pos Position in target array to write to
	 * @return End position in array after writing
	 */
	public abstract int writeRawData(byte [] bs, int pos);
	
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
	 * Converts this String to an equivalent Blob representation. 
	 * Must round trip all values.
	 * @return Blob representation of UTF-8 String
	 */
	@Override
	public abstract ABlob toBlob();

	/**
	 * Convenience method to add a Java String to a CVM String. Not particularly efficient.
	 * @param string String to append
	 * @return CVM String
	 */
	public AString append(String string) {
		return append(Strings.create(string));
	}
	
	@Override
	public final boolean equals(ACell o) {
		if (!(o instanceof AString)) return false;
		return equals((AString)o);
	}
	
	/**
	 * Checks for equality between two strings. Should be optimised
	 * @param b Other string (may be null)
	 * @return True if strings are exactly equal, false otherwise
	 */
	public abstract boolean equals(AString b); 
	
	@Override
	public abstract AString toCanonical();
}
