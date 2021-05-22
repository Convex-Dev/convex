package convex.core.data;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;

import convex.core.exceptions.InvalidDataException;
import convex.core.util.Utils;

/**
 * Class representing a short CVM string.
 */
public class StringShort extends AString {
	
	/**
	 * Length of longest StringShort value that is embedded in chars
	 * 
	 * Just long enough for a 64-char hex string with 0x and 2 delimiters. If that helps.
	 */
	public static final int MAX_EMBEDDED_STRING_LENGTH=68;

	/**
	 * Length of longest StringShort value in chars
	 */
	public static final int MAX_LENGTH=1024;

	
	private String data;

	protected StringShort(String data) {
		super(data.length());
		this.data=data;
	}
	
	/**
	 * Creates a StringShort instance from a regular Java String
	 * 
	 * @param string String to wrap as StringShort
	 * @return StringShort instance, or null if String is of invalid size
	 */
	public static StringShort create(String string) {
		int len=string.length();
		if ((len<0)||(len>MAX_LENGTH)) return null;
		return new StringShort(string);
	}


	@Override
	public char charAt(int index) {
		return data.charAt(index);
	}

	@Override
	public StringShort subSequence(int start, int end) {
		if ((start<0)||(end>length)) throw new IndexOutOfBoundsException("Out of range subSerqnce "+start+","+end);
		if (end<start) throw new IllegalArgumentException("End before start!");
		if ((start==0)&&(end==length)) return this;
		return new StringShort(data.substring(start, end));
	}

	@Override
	public void validateCell() throws InvalidDataException {
		if (length>MAX_LENGTH) throw new InvalidDataException("StringShort too long: " +length,this);
		if (length!=data.length()) throw new InvalidDataException("Wrong String length!",this);
	}

	@Override
	public int encodeRaw(byte[] bs, int pos) {
		pos=Format.writeVLCLong(bs,pos, length);
		
		int n=data.length();
		for (int i=0; i<n; i++) {
			pos=Utils.writeChar(bs, pos, data.charAt(i));
		}
		return pos;
	}

	@Override
	public int estimatedEncodingSize() {
		return 3+length*2;
	}

	@Override
	public boolean isCanonical() {
		return true;
	}
	
	@Override public final boolean isCVMValue() {
		return true;
	}
	
	@Override
	public boolean isEmbedded() {
		return length<=MAX_EMBEDDED_STRING_LENGTH;
	}

	@Override
	public int getRefCount() {
		return 0;
	}

	/**
	 * Read a StringShort from a bytebuffer. Assumes tag and length already read and correct.
	 * 
	 * @param length Length in number of chars to read
	 * @param bb
	 * @return
	 */
	public static AString read(int length, ByteBuffer bb) {
		CharBuffer cb=bb.asCharBuffer();
		cb.limit(length);
		String data=cb.toString();
		
		// advance bb to correct position
		bb.position(bb.position()+length*2);
		
		return new StringShort(data);
	}
	
	@Override
	public String toString() {
		return data;
	}
	
	@Override 
	public int hashCode() {
		return data.hashCode();
	}

	@Override 
	public boolean equals(ACell a) {
		if (a instanceof StringShort) {
			return equals((StringShort)a);
		}
		return false;
	}
	
	public boolean equals(StringShort a) {
		return this.data.equals(a.data);
	}

	@Override
	public int compareTo(AString o) {
		return data.compareTo(o.toString());
	}

	@Override
	protected void appendToStringBuffer(StringBuilder sb, int start, int length) {
		sb.append(data.substring(start, start+length));
	}

	@Override
	protected AString append(char charValue) {
		StringBuilder sb=new StringBuilder(data);
		sb.append(charValue);
		return Strings.create(sb.toString());
	}
}
