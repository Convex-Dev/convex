package convex.core.data;

import java.util.ArrayList;
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
			ACell a=r.getValue();
			// Add all child refs to stack
			int n=a.getRefCount();
			for (int i=n-1; i>=0; i--) {
				al.add(a.getRef(i));
			}
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
}
