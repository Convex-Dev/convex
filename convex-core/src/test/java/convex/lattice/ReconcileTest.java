package convex.lattice;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import convex.core.data.ACell;
import convex.core.data.AHashMap;
import convex.core.data.Keyword;
import convex.core.data.Maps;
import convex.core.data.prim.CVMLong;
import convex.lattice.cursor.ReadOnlyCursorException;
import convex.lattice.cursor.Root;

/**
 * Reconciling a working value with its destination, by a merge given the base.
 */
class ReconcileTest {

	private static final Keyword A=Keyword.intern("a");
	private static final Keyword B=Keyword.intern("b");
	private static final Keyword X=Keyword.intern("x");
	private static final Keyword Y=Keyword.intern("y");
	private static final ACell ONE=CVMLong.ONE;
	private static final ACell OURS=CVMLong.create(1000);
	private static final ACell THEIRS=CVMLong.create(2000);

	@Test void reconcileGivesTheMergeTheBaseAndARetrySeesOnlyWhatIsNew() {
		AHashMap<Keyword,ACell> original=Maps.of(A,ONE);
		var root=new Root<AHashMap<Keyword,ACell>>(original);
		var fork=root.fork();
		fork.set(original.assoc(B,OURS));
		root.set(original.assoc(X,THEIRS));
		var firstDestination=root.get();
		var bases=new ArrayList<AHashMap<Keyword,ACell>>();
		var published=fork.reconcile((base,own,other) -> {
			bases.add(base);
			// A write landing during the merge makes the destination's update repeat
			if (bases.size()==1) root.set(other.assoc(Y,THEIRS));
			// Own changed only B, which nothing else touches
			return other.assoc(B,own.get(B));
		});
		assertEquals(List.of(original,firstDestination),bases);
		assertEquals(Maps.of(A,ONE,B,OURS,X,THEIRS,Y,THEIRS),published);
		assertEquals(published,root.get());
	}

	@Test void aReadOnlyViewRefusesToReconcile() {
		var root=new Root<AHashMap<Keyword,ACell>>(Maps.empty());
		assertThrows(ReadOnlyCursorException.class,() -> root.readOnly().reconcile((base,own,other) -> own));
	}

	@Test void aComponentWithOnlyATwoValueMergeStillUsesIt() {
		AHashMap<Keyword,ACell> original=Maps.of(A,ONE);
		var root=new Root<AHashMap<Keyword,ACell>>(original);
		var fork=root.fork();
		var component=new ALatticeComponent<AHashMap<Keyword,ACell>>(fork) {
			@Override protected AHashMap<Keyword,ACell> reconcile(AHashMap<Keyword,ACell> own,AHashMap<Keyword,ACell> other) {
				return own.assoc(X,OURS);
			}
		};
		fork.set(original.assoc(B,OURS));
		root.set(original.assoc(Y,THEIRS));
		assertEquals(Maps.of(A,ONE,B,OURS,X,OURS),component.sync(),"The two-value rule decides, not a walk of changes");
	}

	@Test void aComponentThatDefinesNoMergeStillRefuses() {
		AHashMap<Keyword,ACell> original=Maps.of(A,ONE);
		var root=new Root<AHashMap<Keyword,ACell>>(original);
		var fork=root.fork();
		var component=new ALatticeComponent<AHashMap<Keyword,ACell>>(fork) {};
		fork.set(original.assoc(B,OURS));
		root.set(original.assoc(Y,THEIRS));
		assertThrows(MergeConflictException.class,component::sync);
	}
}
