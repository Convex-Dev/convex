package convex.core.data;

import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

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
	public static final int MAX_CHARS = 32;

	/** Minimum size of a Keyword in UTF-16 chars representation */
	public static final int MIN_CHARS = 1;

	private Keyword(String name) {
		super(name);
	}

	/**
	 * Creates a Keyword with the given name
	 * 
	 * @param name A String of at least 1 and no more than 64 UTF-8 bytes in length
	 * @return The new Keyword, or null if the name is invalid for a Keyword
	 */
	public static Keyword create(String name) {
		if (!validateName(name)) {
			return null;
		}

		return new Keyword(name);
	}

	/**
	 * Creates a Keyword with the given name, throwing an exception if name is not
	 * valid
	 * 
	 * @param name A String of at least 1 and no more than 64 UTF-8 bytes in length
	 * @return The new Keyword
	 */
	public static Keyword createChecked(String name) {
		Keyword k = create(name);
		if (k == null) throw new IllegalArgumentException("Invalid keyword name: " + name);
		return k;
	}

	@Override
	public boolean isCanonical() {
		return true;
	}

	@Override
	public ByteBuffer writeRaw(ByteBuffer bb) {
		// write contents of Blob - skipping 1 tag byte
		return getEncoding().slice(1).writeRaw(bb);
	}

	/**
	 * Reads a Keyword from the given ByteBuffer, assuming tag already consumed
	 * 
	 * @param bb ByteBuffer source
	 * @return The Keyword read
	 * @throws BadFormatException If a Keyword could not be read correctly
	 */
	public static Keyword read(ByteBuffer bb) throws BadFormatException {
		try {
			int len = bb.get();
			if (len < 0) throw new BadFormatException("Negative keyword length: " + len);
			byte[] data = new byte[len + 2];
			bb.get(data, 2, len);
			data[0] = Tag.KEYWORD;
			data[1] = (byte) len;

			String name = new String(data, 2, len, StandardCharsets.UTF_8);
			if (!validateName(name)) throw new BadFormatException("Invalid keyword name: " + name);
			Keyword k = new Keyword(name);
			// re-use the created array as the Blob for this Keyword
			k.attachEncoding(Blob.wrap(data));
			return k;
		} catch (BufferUnderflowException e) {
			throw new BadFormatException("Buffer underflow", e);
		} catch (IllegalArgumentException e) {
			throw new BadFormatException("Invalid keyword read", e);
		}
	}

	public String getName() {
		return name;
	}

	@Override
	protected Blob createEncoding() {
		byte[] bs = name.getBytes(StandardCharsets.UTF_8);
		int len = bs.length;
		byte[] data = new byte[len + 2];
		data[0] = Tag.KEYWORD;
		data[1] = (byte) len;
		System.arraycopy(bs, 0, data, 2, len);
		return Blob.wrap(data);
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
	public ByteBuffer write(ByteBuffer bb) {
		// write contents of Blob - this includes tag
		return getEncoding().writeRaw(bb);
	}

	@Override
	public int estimatedEncodingSize() {
		return getEncoding().length;
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
		// TODO Auto-generated method stub

	}

	@SuppressWarnings("unchecked")
	@Override
	public Ref<Keyword> getRef() {
		// TODO maybe cache this?
		return RefDirect.create(this);
	}
	
	@Override
	public int getRefCount() {
		return 0;
	}

	@Override
	protected boolean isEmbedded() {
		// Keywords are always embedded
		return true;
	}


}
