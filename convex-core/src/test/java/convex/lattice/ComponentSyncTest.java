package convex.lattice;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;

import convex.core.data.ACell;
import convex.core.data.AHashMap;
import convex.core.data.Index;
import convex.core.data.Keyword;
import convex.core.data.Maps;
import convex.core.data.Sets;
import convex.core.data.SignedData;
import convex.core.data.prim.CVMLong;
import convex.lattice.cursor.ForkedCursor;
import convex.lattice.cursor.ACursor;
import convex.lattice.cursor.AForkableCursor;
import convex.lattice.cursor.Root;
import convex.lattice.cursor.SignedCursor;

/** Publication follows bound storage, independently of editing views. */
class ComponentSyncTest {

	private static final Keyword VALUE=Keyword.intern("value");
	private static final Keyword SIBLING=Keyword.intern("sibling");

	private static final class Value extends ALatticeComponent<CVMLong> {
		Value(AForkableCursor<CVMLong> cursor) { super(cursor); }
		@Override protected Value rebind(AForkableCursor<CVMLong> cursor) { return new Value(cursor); }
		@Override public Value fork() { return rebind(storage.fork()); }

		static Value staged(ACursor<CVMLong> destination) {
			return new Value(ForkedCursor.create(destination));
		}

		@Override protected CVMLong mergePublication(CVMLong own,CVMLong other) {
			if (own==null) return other;
			return other==null || own.longValue()>=other.longValue()?own:other;
		}
	}

	/** Rebinding recreates editing policies and children around exactly the supplied storage. */
	private static final class Record extends ALatticeComponent<AHashMap<Keyword,ACell>>
			implements TransactionalComponent<AHashMap<Keyword,ACell>,Record> {
		private final Supplier<CVMLong> clock;
		private final Timestamped<AHashMap<Keyword,ACell>> edits;
		private final SetComponent<CVMLong> items;

		Record(AForkableCursor<AHashMap<Keyword,ACell>> cursor,Supplier<CVMLong> clock) {
			super(cursor);
			this.clock=clock;
			edits=Timestamped.create(cursor.withDefault(Maps.empty()),clock);
			items=new SetComponent<>(cursor().path(VALUE));
		}

		@Override public AForkableCursor<AHashMap<Keyword,ACell>> cursor() { return edits.cursor(); }
		@Override protected Record rebind(AForkableCursor<AHashMap<Keyword,ACell>> cursor) {
			return new Record(cursor,clock);
		}
		@Override public Record withCursor(AForkableCursor<AHashMap<Keyword,ACell>> cursor) { return rebind(cursor); }
		@Override public AHashMap<Keyword,ACell> merge(AHashMap<Keyword,ACell> incoming) { return edits.merge(incoming); }
		@Override protected AHashMap<Keyword,ACell> mergePublication(AHashMap<Keyword,ACell> own,AHashMap<Keyword,ACell> other) {
			return Timestamped.lattice().merge(own,other);
		}
	}

	@Test void rebindPreservesEditingPolicyAndPublishesBoundStorage() {
		var root=Root.<AHashMap<Keyword,ACell>>create(null);
		var ticks=new AtomicLong();
		var live=new Record(root,() -> CVMLong.create(ticks.incrementAndGet()));
		// Exercise the inherited fork implementation and its virtual rebind hook.
		var draft=assertInstanceOf(Record.class,live.fork());
		assertNull(root.get());
		assertEquals(0,ticks.get());
		draft.items.add(CVMLong.ONE);
		assertNull(root.get(),"Recreated children must edit the new storage");
		assertEquals(1,ticks.get());
		draft.items.sync();
		draft.cursor().sync((a,b) -> { throw new AssertionError("Editing views do not reconcile"); });
		assertNull(root.get(),"Child and editing views must not publish the enclosing fork");
		assertSame(root.get(),live.sync());
		assertSame(draft.cursor().get(),draft.sync());
		assertSame(draft.cursor().get(),root.get());
		assertEquals(1,ticks.get(),"Publication must not stamp local edits again");

		live.items.add(CVMLong.TWO);
		draft.items.add(CVMLong.create(3));
		assertEquals(3,ticks.get());
		draft.sync();
		assertEquals(Sets.of(1,3),live.items.get(),"The newer whole record wins the publication conflict");
		assertEquals(3,ticks.get(),"Reconciliation must use stored timestamps");

		draft.transact(view -> {
			view.items.add(CVMLong.create(4));
			assertEquals(Sets.of(1,3),draft.items.get());
			view.sync(); // Transaction storage is a root, so this cannot publish.
		});
		assertEquals(Sets.of(1,3),live.items.get());
		draft.sync();
		assertEquals(Sets.of(1,3,4),live.items.get());
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
		var first=Value.staged(destination);
		var second=new Value(working).fork();
		first.cursor().set(CVMLong.ONE);
		second.cursor().set(CVMLong.TWO);
		assertSame(CVMLong.ZERO,root.get());
		assertSame(CVMLong.ONE,first.sync());
		assertSame(CVMLong.TWO,second.sync());
		assertSame(CVMLong.TWO,root.get());
		assertSame(root.get(),first.sync(),"An unchanged fork adopts its destination");
		assertSame(root.get(),first.sync(),"Repeated sync preserves identity");
	}

	@Test void wrappersShareTheSuppliedForkPublicationBoundary() {
		var root=Root.create(CVMLong.ZERO);
		var fork=root.fork();
		var view=new Value(fork);
		root.set(CVMLong.ONE);
		assertSame(CVMLong.ONE,view.sync(),"An unchanged fork adopts its destination through any wrapper");
		fork.set(CVMLong.TWO);
		assertSame(CVMLong.TWO,view.sync());
		assertSame(CVMLong.TWO,root.get(),"The bound fork is the publication boundary");
		assertSame(CVMLong.TWO,fork.sync((own,other) -> own));
		assertSame(CVMLong.TWO,root.get());
	}

	@Test void nestedPublicationStopsAtItsDestinationAndPreservesSiblings() {
		var initial=Index.<Keyword,ACell>none().assoc(VALUE,CVMLong.ZERO).assoc(SIBLING,CVMLong.ZERO);
		var root=Root.create(initial);
		var parent=root.fork();
		var child=Value.staged(parent.path(VALUE));
		child.cursor().set(CVMLong.ONE);
		parent.path(SIBLING).set(CVMLong.TWO);
		new Value(parent.path(VALUE)).sync();
		assertSame(CVMLong.ZERO,parent.get().get(VALUE));
		child.sync();
		assertSame(CVMLong.ONE,parent.get().get(VALUE));
		assertSame(CVMLong.TWO,parent.get().get(SIBLING));
		assertSame(initial,root.get(),"Child publication must not publish its destination further");
		parent.sync((own,other) -> own);
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
