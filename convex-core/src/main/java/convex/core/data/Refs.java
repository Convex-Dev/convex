package convex.core.data;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Consumer;

import convex.core.util.Trees;

/**
 * Static utilities for working with Refs
 */
public class Refs {
	
	/**
	 * Visit all Refs in a tree of Refs, in depth first order
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
	
	
	
	public static final class RefTreeStats {
		Ref<?> root=null;
		long total=0;
		long embedded=0;
		long direct=0;
		long persisted=0;
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
	 * Counts the total number of Refs contained in a data object recursively. Will
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
}
