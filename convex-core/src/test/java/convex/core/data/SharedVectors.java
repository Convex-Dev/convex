package convex.core.data;

import java.util.HashMap;

/**
 * Vectors that claim large counts through shared nodes, as a vector received from
 * another peer can: every node below the top repeats one child, so the vector takes
 * little memory whatever count it claims.
 */
public class SharedVectors {

	/**
	 * Creates a vector of one repeated value.
	 *
	 * @param <T> Element type
	 * @param value Element value
	 * @param count Number of elements, a positive multiple of 16
	 * @return Vector of the given count
	 */
	public static <T extends ACell> AVector<T> repeat(T value, long count) {
		if ((count<=0)||(count%Vectors.CHUNK_SIZE!=0)) throw new IllegalArgumentException("Count must be a positive multiple of 16: "+count);
		HashMap<Long,AVector<T>> full=new HashMap<>();
		full.put((long)Vectors.CHUNK_SIZE,Vectors.repeat(value,Vectors.CHUNK_SIZE));
		return build(count,full);
	}

	private static <T extends ACell> AVector<T> build(long count, HashMap<Long,AVector<T>> full) {
		AVector<T> done=full.get(count);
		if (done!=null) return done;
		long childSize=1L<<VectorTree.computeShift(count);
		int n=(int)((count+childSize-1)/childSize);
		ACell[] children=new ACell[n];
		AVector<T> fullChild=build(childSize,full);
		for (int i=0; i<n-1; i++) children[i]=fullChild;
		children[n-1]=build(count-(n-1)*childSize,full);
		AVector<T> result=VectorTree.unsafeCreate(count,children);
		if (count==childSize*Vectors.CHUNK_SIZE) full.put(count,result);
		return result;
	}
}
