package convex.lattice;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import convex.core.data.ACell;
import convex.core.data.AHashMap;
import convex.core.data.Index;
import convex.core.data.Keyword;
import convex.core.data.Maps;
import convex.core.data.Sets;
import convex.core.data.SignedData;
import convex.core.data.prim.CVMLong;
import convex.lattice.cursor.ACursor;
import convex.lattice.cursor.AForkableCursor;
import convex.lattice.cursor.Root;
import convex.lattice.cursor.SignedCursor;

/** Publication belongs to the component that explicitly creates the boundary. */
class ComponentSyncTest {

	private static final Keyword VALUE=Keyword.intern("value");
	private static final Keyword SIBLING=Keyword.intern("sibling");

	private static final class Value extends ALatticeComponent<CVMLong> {
		Value(AForkableCursor<CVMLong> cursor) { super(cursor); }
		Value(ForkTarget<CVMLong> target) { super(target); }

		static Value staged(ACursor<CVMLong> destination) {
			return new Value(forkOf(destination));
		}

		@Override protected CVMLong mergePublication(CVMLong own,CVMLong other) {
			if (own==null) return other;
			return other==null || own.longValue()>=other.longValue()?own:other;
		}
	}

	@Test void liveSyncPreservesAbsentAndPresentStorage() {
		var root=Root.<CVMLong>create(null);
		var view=new Value(root);
		assertSame(root,view.cursor());
		assertNull(view.sync());
		assertNull(root.get());
		view.cursor().set(CVMLong.ONE);
		assertSame(CVMLong.ONE,root.get(),"Live edits are immediately visible");
		assertSame(root.get(),view.sync());
		assertSame(root.get(),view.sync());
	}

	@Test void explicitStagingDoesNotDependOnCursorDeclaration() {
		var root=Root.create(CVMLong.ZERO);
		ACursor<CVMLong> destination=root;
		AForkableCursor<CVMLong> working=root;
		var first=new Value(Value.forkOf(destination));
		var second=new Value(Value.forkOf(working));
		first.cursor().set(CVMLong.ONE);
		second.cursor().set(CVMLong.TWO);
		assertSame(CVMLong.ZERO,root.get());
		assertSame(CVMLong.ONE,first.sync());
		assertSame(CVMLong.TWO,second.sync());
		assertSame(CVMLong.TWO,root.get());
		assertSame(root.get(),first.sync(),"An unchanged fork adopts its destination");
		assertSame(root.get(),first.sync(),"Repeated sync preserves identity");
	}

	@Test void viewDoesNotPublishOrRefreshACallerOwnedFork() {
		var root=Root.create(CVMLong.ZERO);
		var fork=root.fork((own,other) -> own);
		var view=new Value(fork);
		root.set(CVMLong.ONE);
		assertSame(CVMLong.ZERO,view.sync(),"A view must not refresh its caller's fork");
		fork.set(CVMLong.TWO);
		assertSame(CVMLong.TWO,view.sync());
		assertSame(CVMLong.ONE,root.get(),"A view must not publish its caller's fork");
		assertSame(CVMLong.TWO,fork.sync());
		assertSame(CVMLong.TWO,root.get());
	}

	@Test void nestedPublicationStopsAtItsDestinationAndPreservesSiblings() {
		var initial=Index.<Keyword,ACell>none().assoc(VALUE,CVMLong.ZERO).assoc(SIBLING,CVMLong.ZERO);
		var root=Root.create(initial);
		var parent=root.fork((own,other) -> own);
		var child=Value.staged(parent.path(VALUE));
		child.cursor().set(CVMLong.ONE);
		parent.path(SIBLING).set(CVMLong.TWO);
		new Value(child.cursor()).sync();
		assertSame(CVMLong.ZERO,parent.get().get(VALUE));
		child.sync();
		assertSame(CVMLong.ONE,parent.get().get(VALUE));
		assertSame(CVMLong.TWO,parent.get().get(SIBLING));
		assertSame(initial,root.get(),"Child publication must not publish its destination further");
		parent.sync();
		assertSame(parent.get(),root.get());
	}

	@Test void defaultSyncThroughSigningStampingAndDefaultsDoesNotAuthorState() {
		var root=Root.<SignedData<AHashMap<Keyword,ACell>>>create(null);
		var signed=SignedCursor.create(root,value -> {
			throw new AssertionError("Sync of a live view must not sign");
		});
		var record=Timestamped.create(signed.withDefault(Maps.empty()),() -> {
			throw new AssertionError("Sync of a live view must not read the clock");
		});
		var items=record.cursor().path(VALUE).withDefault(Sets.empty());
		var view=new ALatticeComponent<>(items) {};
		assertSame(items.get(),view.sync());
		assertSame(items.get(),view.sync());
		assertSame(Maps.empty(),record.sync());
		assertNull(root.get(),"Default containers must remain virtual");
	}
}
