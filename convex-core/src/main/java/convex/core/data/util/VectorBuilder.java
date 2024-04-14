package convex.core.data.util;

import convex.core.data.ACell;
import convex.core.data.ASequence;
import convex.core.data.AVector;
import convex.core.data.VectorLeaf;
import convex.core.data.Vectors;
import convex.core.util.Utils;

/**
 * Similar to Java StringBuilder designed for concatenating multiple small Vectors to produce a larger 
 * Vector. 
 * @param <T> Type of Vector elements
 */
public class VectorBuilder<T extends ACell> {
	private static final int CHUNK_LENGTH=Vectors.CHUNK_SIZE;

	/**
	 * Accumulator Vector, always a canonical Vector with filled chunks
	 */
	@SuppressWarnings("unchecked")
	protected AVector<T> acc=(AVector<T>) VectorLeaf.EMPTY; 
	
	/**
	 * Accumulator array for up to a single chunk worth of cells
	 */
	protected final ACell[] tail=new ACell[CHUNK_LENGTH]; 
	
	/**
	 * Total count of elements in this VectorBuilder
	 */
	protected long count=0;
	
	public VectorBuilder() {
		count=0;
	}
	
	public VectorBuilder(ASequence<T> seq) {
		this();
		concat(seq);
	}

	/**
	 * Available bytes to add to current tail before overflow
	 * @return
	 */
	private int spare() {
		return (int)(CHUNK_LENGTH-(count-acc.count()));
	}
	
	/**
	 * Available bytes to add to current tail before overflow
	 * @return
	 */
	private int arrayPos() {
		return Utils.checkedInt(count-acc.count());
	}
	
	public VectorBuilder<T> append(T a) {
		return this;
	}
	
	public VectorBuilder<T> concat(ASequence<T> b) {
		long blen=b.count();
		if (blen==0) return this; // nothing to do
		
		long spare=spare();
		
		if (blen<=spare) {
			int pos=arrayPos();
			// Does not overflow current chunk
			for (int i=0; i<blen; i++) {
				tail[pos++]=b.get(i);
			}
			count+=blen;
			if (blen==spare) completeChunk();
		} else {
			// append to fill current chunk
			concat(b.slice(0, spare));
			
			// Index over offset into b
			for (long off=spare; off<blen; off+=CHUNK_LENGTH) {
				long take=Math.min(CHUNK_LENGTH, blen-off);
				concat(b.slice(off,off+take));
			}
		}
		return this;
	}
	
	public VectorBuilder<T> conj(T b) {
		int pos=arrayPos();
		tail[pos++]=b;
		count++;
		if (pos==CHUNK_LENGTH) completeChunk();
		return this;
	}

	/**
	 * Completes a chunk to the accumulated Vector
	 */
	private void completeChunk() {
		if (tail.length!=CHUNK_LENGTH) throw new Error("tail not complete! Has length: "+tail.length);
		VectorLeaf<T> b=VectorLeaf.create(tail,0,CHUNK_LENGTH);
		acc=acc.concat(b);
	}
	
	public AVector<T> toVector() {
		AVector<T> result=acc;
		int tlen=(int)(count-acc.count());
		if (tlen==0) return acc;
		
		result=result.concat(Vectors.create(tail, 0, tlen));
		if (result.count()!=count) throw new Error("Invalid count!!");
		return result;
	}

	public long count() {
		return count;
	}
	
	/**
	 * Checks if this VectorBuilder count is less than or equal to the specified limit
	 * @param limit Maximum allowable length of VectorBuilder
	 * @return true if within limit, false if exceeded
	 */
	public boolean check(long limit) {
		return count<=limit;
	}

	/**
	 * Clears this VectorBuilder, preparing for new appends to an empty Blob
	 */
	public void clear() {
		acc=Vectors.empty();
		count=0;
	}



}
