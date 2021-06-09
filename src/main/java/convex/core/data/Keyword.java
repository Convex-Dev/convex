package convex.core.data;

import java.nio.ByteBuffer;

import convex.core.Constants;
import convex.core.data.type.AType;
import convex.core.data.type.Types;
import convex.core.exceptions.BadFormatException;
import convex.core.exceptions.InvalidDataException;

/**
 * Keyword data type. Intended as human-readable map keys, tags and option
 * specifiers etc.
 * 
 * Keywords evaluate to themselves, and as such can be considered as literal
 * constants.
 *
 * "Programs must be written for people to read, and only incidentally for
 * machines to execute." ― Harold Abelson
 */
public class Keyword extends ASymbolic implements Comparable<Keyword> {

	/** Maximum size of a Keyword in UTF-16 chars representation */
	public static final int MAX_CHARS = Constants.MAX_NAME_LENGTH;

	/** Minimum size of a Keyword in UTF-16 chars representation */
	public static final int MIN_CHARS = 1;

	private Keyword(String name) {
		super(name);
	}
	
	public AType getType() {
		return Types.KEYWORD;
	}

	/**
	 * Creates a Keyword with the given name
	 * 
	 * @param name A String to use as the keyword name
	 * @return The new Keyword, or null if the name is invalid for a Keyword
	 */
	public static Keyword create(String name) {
		if (!validateName(name)) {
			return null;
		}
		return new Keyword(name);
	}
	
	public static Keyword create(AString name) {
		if (name==null) return null;
		return create(name.toString());
	}

	/**
	 * Creates a Keyword with the given name, throwing an exception if name is not
	 * valid
	 * 
	 * @param aString A String of at least 1 and no more than 64 UTF-8 bytes in length
	 * @return The new Keyword
	 */
	public static Keyword createChecked(AString aString) {
		Keyword k = create(aString);
		if (k == null) throw new IllegalArgumentException("Invalid keyword name: " + aString);
		return k;
	}
	
	public static Keyword createChecked(String aString) {
		Keyword k = create(aString);
		if (k == null) throw new IllegalArgumentException("Invalid keyword name: " + aString);
		return k;
	}


	@Override
	public boolean isCanonical() {
		return true;
	}


	/**
	 * Reads a Keyword from the given ByteBuffer, assuming tag already consumed
	 * 
	 * @param bb ByteBuffer source
	 * @return The Keyword read
	 * @throws BadFormatException If a Keyword could not be read correctly
	 */
	public static Keyword read(ByteBuffer bb) throws BadFormatException {
		String name=Format.readUTF8String(bb);
		Keyword kw = Keyword.create(name);
		if (kw == null) throw new BadFormatException("Can't read symbol");
		return kw;

	}
	
	@Override
	public int encode(byte[] bs, int pos) {
		bs[pos++]=Tag.KEYWORD;
		return encodeRaw(bs,pos);
	}

	@Override
	public int encodeRaw(byte[] bs, int pos) {
		return Format.writeRawUTF8String(bs, pos, name);
	}

	@Override
	public void ednString(StringBuilder sb) {
		print(sb);
	}
	
	@Override
	public void print(StringBuilder sb) {
		sb.append(':');
		sb.append(name);
	}

	@Override
	public int estimatedEncodingSize() {
		return name.length()*2+3;
	}

	@Override
	public boolean equals(ACell other) {
		if (other == this) return true;
		if (!(other instanceof Keyword)) return false;
		return name.equals(((Keyword) other).name);
	}

	@Override
	public int hashCode() {
		return name.hashCode();
	}

	@Override
	public int compareTo(Keyword k) {
		return name.compareTo(k.name);
	}

	@Override
	public void validateCell() throws InvalidDataException {
		super.validateCell();
	}
	
	@Override
	public int getRefCount() {
		return 0;
	}

	@Override
	public boolean isEmbedded() {
		// Keywords are always embedded
		return true;
	}

	@Override
	public byte getTag() {
		return Tag.KEYWORD;
	}

}
