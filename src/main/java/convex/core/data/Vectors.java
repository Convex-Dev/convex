package convex.core.data;

import java.nio.ByteBuffer;
import java.util.Collection;

import org.bouncycastle.util.Arrays;

import convex.core.exceptions.BadFormatException;
import convex.core.lang.RT;
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
	public static <T extends ACell> AVector<T> create(ACell[] elements, int offset, int length) {
		if (length < 0) throw new IllegalArgumentException("Cannot create vector of negative length!");
		if (length <= CHUNK_SIZE) return VectorLeaf.create(elements, offset, length);
		int tailLength = Utils.checkedInt((length >> BITS_PER_LEVEL) << BITS_PER_LEVEL);
		AVector<T> tail = Vectors.createChunked(elements, offset, tailLength);
		if (tail.count() == length) return tail;
		return VectorLeaf.create(elements, offset + tailLength, length - tailLength, tail);
	}

	/**
	 * Create a canonical vector using blocks. Suitable for a ListVector tail.
	 * 
	 * @param elements
	 * @param offset
	 * @param length
	 * @return A vector, which must consist of a positive number of complete chunks.
	 */
	static <T extends ACell> AVector<T> createChunked(Object[] elements, int offset, int length) {
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
	public static <T extends ACell> AVector<T> create(ACell[] elements) {
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
	public static <R extends ACell, T extends ACell> AVector<R> create(Collection<?> list) {
		if (list instanceof ASequence) return create((ASequence<R>) list);
		if (list.size() == 0) return empty();
		ACell[] cells=Utils.toCellArray(list.toArray());
		return (AVector<R>) create(cells);
	}
	
	public static <R extends ACell, T extends ACell> AVector<R> create(ASequence<R> list) {
		if (list instanceof AVector) return (AVector<R>) list;
		if (list.size() == 0) return empty();
		return create(list.toCellArray());
	}


	@SuppressWarnings("unchecked")
	public static <T extends ACell> AVector<T> empty() {
		return (AVector<T>) VectorLeaf.EMPTY;
	}

	/**
	 * Creates a vector with the given values. Performs conversion to CVM types.
	 * @param <T>
	 * @param elements
	 * @return New Vector
	 */
	@SuppressWarnings("unchecked")
	@SafeVarargs
	public static <T extends ACell> AVector<T> of(Object... elements) {
		int n=elements.length;
		ACell[] es= new ACell[n];
		for (int i=0; i<n; i++) {
			Object v=elements[i];
			es[i]=(T)RT.cvm(v);
		}
		return create(es, 0, n);
	}

	@SuppressWarnings("unchecked")
	public static <T extends ACell> AVector<T> repeat(T m, int count) {
		ACell[] obs = new ACell[count];
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
	public static <T extends ACell> AVector<T> read(ByteBuffer bb) throws BadFormatException {
		long count = Format.readVLCLong(bb);
		if ((count <= VectorLeaf.MAX_SIZE) || ((count & 0x0F) != 0)) {
			return VectorLeaf.read(bb, count);
		} else {
			return VectorTree.read(bb, count);
		}
	}

}
