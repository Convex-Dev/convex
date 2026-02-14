package convex.core.data;

import convex.core.exceptions.BadFormatException;

/**
 * Abstract base class for encoders, which convert data to / from Blob instances.
 *
 * Subclasses implement tag-based dispatch in {@link #read(byte, Blob, int)}.
 */
public abstract class AEncoder<T> {

	public static final int FORMAT_V1=1;

	/**
	 * Encodes a value as Blob
	 * @param a Value to encode
	 * @return Blob encoding in CAD3 format
	 */
	public abstract Blob encode(T a);

	/**
	 * Decodes a single value from a complete Blob encoding.
	 * @param encoding Blob containing the full encoding
	 * @return Decoded value
	 * @throws BadFormatException If encoding is empty or invalid
	 */
	public T decode(Blob encoding) throws BadFormatException {
		if (encoding.count()<1) throw new BadFormatException("Empty encoding");
		return read(encoding,0);
	}

	/**
	 * Reads a value from a Blob at the given offset. Extracts the tag byte
	 * and delegates to {@link #read(byte, Blob, int)}.
	 * @param encoding Blob to read from
	 * @param offset Offset of tag byte
	 * @return Decoded value
	 * @throws BadFormatException If encoding is invalid
	 */
	protected T read(Blob encoding, int offset) throws BadFormatException {
		byte tag = encoding.byteAtUnchecked(offset);
		return read(tag,encoding,offset);
	}

	/**
	 * Reads a value from a Blob given the tag byte. Subclasses implement
	 * tag-based dispatch here.
	 * @param tag Tag byte (first byte of encoding)
	 * @param encoding Blob to read from
	 * @param offset Offset of tag byte in blob
	 * @return Decoded value
	 * @throws BadFormatException If encoding is invalid for the given tag
	 */
	public abstract T read(byte tag, Blob encoding, int offset) throws BadFormatException;

	/**
	 * Reads a value from a Blob of multi-cell encoded data
	 * @param enc Data to decode (top cell followed by children)
	 * @return Decoded value
	 * @throws BadFormatException If encoding format is invalid
	 */
	@SuppressWarnings("unchecked")
	public T decodeMultiCell(Blob enc) throws BadFormatException {
		return (T) Format.decodeMultiCell(enc);
	}
}
