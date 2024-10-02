package convex.core.data;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Consumer;

import convex.core.store.AStore;
import convex.core.util.Trees;

/**
 * Static utilities for working with Refs
 */
public class Refs {
	
	/**
	 * Visit all Refs in a tree of Refs, in depth first order.
	 * Requires O(1) stack, O(n) time and O(depth) memory.
	 * 
	 * @param root Root of Ref tree to visit
	 * @param visitor Visitor function to call for each Ref
	 */
	public static void visitAllRefs(Ref<?> root, Consumer<Ref<?>> visitor) {
		ArrayList<Ref<?>> al=new ArrayList<>();
		al.add(root);
		
		Consumer<Ref<?>> addingVisitor=r->{
			visitor.accept(r);
			pushChildRefs(al,r);
		};
		
		Trees.visitStack(al,addingVisitor);
	}
	
	/**
	 * Checks the complete tree of Refs is consistent with the given store
	 * @param root Root of Ref tree to visit
	 * @param store Store to check consistency with
	 */
	public static void checkConsistentStores(Ref<?> root, AStore store) {
		visitAllRefs(root,r->{
			if (r instanceof RefSoft) {
				RefSoft<?> rs=(RefSoft<?>)r;
				AStore rstore=rs.getStore();
				if (!(rstore==store)) {
					String msg="Inconsistent store! "+rs+" expected "+store+ " but was "+rstore;
					msg+=" in Cell of type: "+r.getValue().getClass();
					throw new IllegalStateException(msg);
				}
			}
		});
	}
	
	public static final class RefTreeStats {
		public Ref<?> root=null;
		public long total=0;
		public long embedded=0;
		public long direct=0;
		public long persisted=0;
		public long stored=0;
	}
	
	/**
	 * Gets statistics for a tree of Refs
	 * @param root Root node of Ref Tree
	 * @return Stats object
	 */
	public static RefTreeStats getRefTreeStats(Ref<?> root) {
		RefTreeStats rts=new RefTreeStats();
		rts.root=root;
		
		Consumer<Ref<?>> statVisitor=r->{
			rts.total++;
			if (r.isEmbedded()) rts.embedded++;
			if (r.isDirect()) rts.direct++;
			if (r.getStatus()>=Ref.PERSISTED) rts.persisted++;
			if (r.getStatus()>=Ref.STORED) rts.stored++;
		};
		visitAllRefs(root,statVisitor);
		
		return rts;
	}

	/**
	 * Accumulates the set of all unique Refs in the given object.
	 * 
	 * @param a Ref or Cell
	 * @return Set containing all unique refs (accumulated recursively) within the
	 *         given object
	 */
	public static java.util.Set<Ref<?>> accumulateRefSet(ACell a) {
		return accumulateRefSet(Ref.get(a));
	}
	
	public static java.util.Set<Ref<?>> accumulateRefSet(Ref<?> root) {
		HashSet<Ref<?>> hs = new HashSet<>();
		accumulateRefSet(root, hs);
		return hs;
	}

	static void accumulateRefSet(Ref<?> root, HashSet<Ref<?>> hs) {
		ArrayList<Ref<?>> al=new ArrayList<>();
		al.add(root);
		
		Consumer<Ref<?>> accVisitor=r->{
			if (!hs.contains(r)) {
				hs.add(r);
				
				// Add all child refs to stack
				pushChildRefs(al,r);
			}
		};

		Trees.visitStack(al, accVisitor);
	}
	
	static <T extends ACell> void pushChildRefs(ArrayList<Ref<?>> stack, Ref<T> r) {
		T a=r.getValue();
		if (a==null) return;
		int n=a.getRefCount();
		for (int i=n-1; i>=0; i--) {
			stack.add(a.getRef(i));
		}
	}

	/**
	 * Counts the total number of Refs contained in a data object recursively, including the given cell. Will
	 * count duplicate children multiple times.
	 *
	 * @param a Object to count Refs in
	 * @return Total number of Refs found
	 */
	public static long totalRefCount(ACell a) {
		Ref<?> r=Ref.get(a);
		RefTreeStats rts=getRefTreeStats(r);
		return rts.total;
	}
	
	/**
	 * Counts the number of logically unique Refs for a Cell, i.e. the number of unique cell values
	 * (including this Cell)
	 *
	 * @param a Cell to count Refs in
	 * @return Number of Refs found
	 */
	public static long uniqueRefCount(ACell a) {
		Set<Ref<?>> rs=accumulateRefSet(a);
		return rs.size();
	}
	



	/**
	 * Utility function to locate missing data
	 * @param sb StringBuilder to append to
	 * @param value Value to search for missing data
	 */
	public static void printMissingTree(StringBuilder sb, ACell value) {
		printTree(sb,Ref.get(value),0);
	}
	

	public static String printMissingTree(ACell value) {
		StringBuilder sb=new StringBuilder();
		printMissingTree(sb,value);
		return sb.toString();
	}

	private static void printTree(StringBuilder sb, Ref<ACell> ref, int indent) {
		for (int i=0; i<indent; i++) {
			sb.append(' ');
		}
		sb.append(ref.isEmbedded()?'-':'=');

		sb.append(' ');
		if (ref.isMissing()) {
			sb.append("Missing: "+ref.getHash()+"\n");
			return;
		}
		ACell v=ref.getValue();
		if (v==null) {
			sb.append("nil\n");
			return;
		}
		sb.append(v.getClass().getSimpleName()+"\n");
		int rc=v.getRefCount();
		for (int i=0; i<rc; i++) {
			printTree(sb,v.getRef(i),indent+1);
		}
	}

	/**
	 * Filters an array of Refs, returning an array containing only the elements where the
	 * mask bit is set. May return the same array if all elements areincluded.
	 *
	 * @param arr Array to filter
	 * @param mask Mask of elements to include
	 * @return Filtered array.
	 */
	@SuppressWarnings("unchecked")
	public static <T extends ACell> Ref<T>[] filterSmallArray(Ref<T>[] arr, int mask) {
		int n = arr.length;
		if (n > 32) throw new IllegalArgumentException("Array too long to filter: " + n);
		int fullMask = (1 << n) - 1;
		if (mask == fullMask) return arr;
		int nn = Integer.bitCount(mask);
		Ref<T>[] result = new Ref[nn];
		if (nn == 0) return result;
		int ix = 0;
		for (int i = 0; i < n; i++) {
			if ((mask & (1 << i)) != 0) {
				result[ix++] = arr[i];
			}
		}
		assert (ix == nn);
		return result;
	}

}
