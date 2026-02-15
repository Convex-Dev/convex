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
	 * Mutable decode cursor over a byte array. Format-independent:
	 * just tracks position in raw bytes. No domain-specific operations.
	 *
	 * Constructed from a Blob; extracts the backing byte[] for direct
	 * array access without Blob indirection on every byte read.
	 */
	public static class DecodeState {
		/** Backing byte array (from Blob.getInternalArray()) */
		public final byte[] data;

		/** Current absolute position in data[] */
		public int pos;

		/** End boundary (absolute index in data[]) */
		public final int limit;

		public DecodeState(Blob source) {
			this.data = source.getInternalArray();
			this.pos = source.getInternalOffset();
			this.limit = this.pos + (int) source.count();
		}

		/** Read and advance past one byte */
		public byte readByte() {
			return data[pos++];
		}

		/**
		 * Advance position to a new absolute index. Throws if newPos
		 * exceeds the limit, catching overruns early.
		 * @param newPos New absolute position in data[]
		 */
		public void advanceTo(int newPos) {
			if (newPos > limit) throw new IndexOutOfBoundsException("Decode position exceeds limit ");
			pos = newPos;
		}

		/** Remaining bytes available */
		public int remaining() {
			return limit - pos;
		}

		/**
		 * Attach encoding from [startPos, current pos) onto a cell.
		 * Uses Blob.wrap to share the backing array (no copy).
		 * @param cell Cell to attach encoding to
		 * @param startPos Start position of the encoding
		 */
		public void attachEncoding(ACell cell, int startPos) {
			cell.attachEncoding(Blob.wrap(data, startPos, pos - startPos));
		}
	}

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
	 * Decodes a value from multi-cell encoded data (top cell followed by
	 * VLQ-prefixed children). Dispatches through this encoder's virtual
	 * {@link #read} methods for type-appropriate decoding.
	 *
	 * @param encoding Data to decode (top cell followed by children)
	 * @return Decoded value
	 * @throws BadFormatException If encoding format is invalid
	 */
	public abstract T decodeMultiCell(Blob encoding) throws BadFormatException;
}
