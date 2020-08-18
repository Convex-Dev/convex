package convex.core.data;

import java.nio.ByteBuffer;
import java.util.Collection;

import org.bouncycastle.util.Arrays;

import convex.core.exceptions.BadFormatException;
import convex.core.util.Utils;

public class Vectors {

	protected static final int BITS_PER_LEVEL = 4;
	protected static final int CHUNK_SIZE = 1 << BITS_PER_LEVEL; // 16
	protected static final int BITMASK = CHUNK_SIZE - 1; // 15

	/**
	 * Creates a canonical AVector with the given elements
	 * 
	 * @param elements
	 * @param offset
	 * @param length
	 * @return New vector with the specified elements
	 */
	public static <T> AVector<T> create(T[] elements, int offset, int length) {
		if (length < 0) throw new IllegalArgumentException("Cannot create vector of negative length!");
		if (length <= CHUNK_SIZE) return VectorLeaf.create(elements, offset, length);
		int tailLength = Utils.checkedInt((length >> BITS_PER_LEVEL) << BITS_PER_LEVEL);
		AVector<T> tail = Vectors.createChunked(elements, offset, tailLength);
		if (tail.count() == length) return tail;
		return VectorLeaf.create(elements, offset + tailLength, length - tailLength, tail);
	}

	/**
	 * Create a vector using blocks. Suitable for a ListVector tail.
	 * 
	 * @param elements
	 * @param offset
	 * @param length
	 * @return A vector, which must consist of a positive number of complete chunks.
	 */
	static <T> AVector<T> createChunked(T[] elements, int offset, int length) {
		if ((length == 0) || (length & BITMASK) != 0)
			throw new IllegalArgumentException("Invalid vector length: " + length);
		if (length == CHUNK_SIZE) return VectorLeaf.create(elements, offset, length);
		return VectorTree.create(elements, offset, length);
	}

	/**
	 * Create a vector from an array of elements.
	 * 
	 * @param <T>
	 * @param elements
	 * @return New vector with the specified elements
	 */
	public static <T> AVector<T> create(T[] elements) {
		return create(elements, 0, elements.length);
	}

	/**
	 * Concerts a collection to a vector. Not necessarily the most efficient.
	 * Performs an unchecked cast.
	 * 
	 * @param <R>  Type of Vector elements to produce
	 * @param <T>  Type of source collection elements
	 * @param list
	 * @return New vector with the specified collection of elements
	 */
	@SuppressWarnings("unchecked")
	public static <R, T> AVector<R> create(Collection<?> list) {
		if (list instanceof AVector) return (AVector<R>) list;
		if (list.size() == 0) return empty();
		return (AVector<R>) create(list.toArray());
	}

	@SuppressWarnings("unchecked")
	public static <T> AVector<T> empty() {
		return (AVector<T>) VectorLeaf.EMPTY;
	}

	@SafeVarargs
	public static <T> AVector<T> of(T... elements) {
		return create(elements, 0, elements.length);
	}

	@SuppressWarnings("unchecked")
	public static <T> AVector<T> repeat(T m, int count) {
		// TODO: could duplicate Refs as performance enhancement? Probably not important
		// though
		Object[] obs = new Object[count];
		Arrays.fill(obs, m);
		return (AVector<T>) create(obs);
	}

	/**
	 * Reads a Vector for the specified bytebuffer. Assumes Tag byte already consumed.
	 * 
	 * Distinguishes between child types according to count.
	 * 
	 * @param <T>
	 * @param bb
	 * @return Vector read from ByteBuffer
	 * @throws BadFormatException
	 */
	public static <T> AVector<T> read(ByteBuffer bb) throws BadFormatException {
		long count = Format.readVLCLong(bb);
		if ((count <= VectorLeaf.MAX_SIZE) || ((count & 0x0F) != 0)) {
			return VectorLeaf.read(bb, count);
		} else {
			return VectorTree.read(bb, count);
		}
	}

}
