package convex.core.data;

import java.util.Collection;

import org.bouncycastle.util.Arrays;

import convex.core.exceptions.BadFormatException;
import convex.core.lang.RT;
import convex.core.util.Utils;

/**
 * Static utility functions for working with Vectors
 */
public class Vectors {

	protected static final int BITS_PER_LEVEL = 4;
	
	public static final int CHUNK_SIZE = 1 << BITS_PER_LEVEL; // 16
	
	protected static final int BITMASK = CHUNK_SIZE - 1; // 15
	
	public static final int MAX_ENCODING_LENGTH = Math.max(VectorLeaf.MAX_ENCODING_LENGTH,VectorTree.MAX_ENCODING_LENGTH);

	/**
	 * Creates a canonical AVector with the given elements
	 * 
	 * @param elements Elements to include
	 * @param offset Offset into element array
	 * @param length Number of elements to take
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
	static <T extends ACell> AVector<T> createChunked(ACell[] elements, int offset, int length) {
		if ((length == 0) || (length & BITMASK) != 0)
			throw new IllegalArgumentException("Invalid vector length: " + length);
		if (length == CHUNK_SIZE) return VectorLeaf.create(elements, offset, length);
		return VectorTree.create(elements, offset, length);
	}

	/**
	 * Create a vector from an array of elements.
	 * 
	 * @param <T> Type of elements
	 * @param elements Elements to include
	 * @return New vector with the specified elements
	 */
	public static <T extends ACell> AVector<T> create(ACell... elements) {
		return create(elements, 0, elements.length);
	}
	
	/**
	 * Create a vector directly wrapping an array of cells. Do not mutate array after creation!
	 * 
	 * @param <T> Type of elements
	 * @param elements Elements to include
	 * @return New vector wrapping the specified elements
	 */
	public static <T extends ACell> AVector<T> wrap(ACell[] elements) {
		if (elements.length==0) return empty();
		return VectorArray.wrap(elements);
	}

	/**
	 * Coerces a collection to a vector. Not necessarily the most efficient.
	 * Performs an unchecked cast.
	 * 
	 * @param <R>  Type of Vector elements to produce
	 * @param <T>  Type of source collection elements
	 * @param elements Elements to include
	 * @return New vector with the specified collection of elements
	 */
	@SuppressWarnings("unchecked")
	public static <R extends ACell, T extends ACell> AVector<R> create(Collection<? extends ACell> elements) {
		if (elements instanceof ASequence) return ((ASequence<R>) elements).toVector();
		if (elements.isEmpty()) return empty();
		ACell[] cells=Cells.toCellArray(elements);
		return wrap(cells);
	}
	
	/**
	 * Creates a Vector from the contents of an arbitrary sequence
	 * @param <R> Type of sequence elements
	 * @param list Any sequential collection
	 * @return Vector of elements
	 */
	@SuppressWarnings("unchecked")
	public static <R extends ACell> AVector<R> create(ASequence<R> list) {
		if (list instanceof AVector) return (AVector<R>) list.getCanonical();
		return wrap(list.toCellArray());
	}

	@SuppressWarnings("unchecked")
	public static <T extends ACell> AVector<T> empty() {
		return (VectorLeaf<T>) VectorLeaf.EMPTY;
	}

	/**
	 * Creates a vector with the given values. Performs conversion to CVM types.
	 * @param <T> Type of elements (after CVM conversion)
	 * @param elements Elements to include
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
		return wrap(es);
	}

	@SuppressWarnings("unchecked")
	public static <T extends ACell> AVector<T> repeat(T m, int count) {
		// TODO: should probably be smart and create duplicated Vectors
		ACell[] obs = new ACell[count];
		Arrays.fill(obs, m);
		return (AVector<T>) create(obs);
	}

	/**
	 * Reads a Vector for the specified Blob. Assumes Tag byte already checked.
	 * 
	 * Distinguishes between child types according to count.
	 * 
	 * @param <T> Type of elements
	 * @param b Blob to read from
	 * @param pos Start position in Blob (location of tag byte)
	 * @return New decoded instance
	 * @throws BadFormatException In the event of any encoding error
	 */
	public static <T extends ACell> AVector<T> read(Blob b, int pos) throws BadFormatException {
		long count = Format.readVLQCount(b,pos+1);
		if (count < 0) throw new BadFormatException("Negative length");
		if (VectorLeaf.isValidCount(count)) {
			return VectorLeaf.read(count,b,pos);
		} else {
			return VectorTree.read(count,b,pos);
		}
	}

}
