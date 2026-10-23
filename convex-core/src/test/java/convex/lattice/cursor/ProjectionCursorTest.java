package convex.lattice.cursor;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import convex.core.crypto.AKeyPair;
import convex.core.data.ACell;
import convex.core.data.AHashMap;
import convex.core.data.Keyword;
import convex.core.data.Maps;
import convex.core.data.prim.CVMLong;

/** Projection and authoring views share atomic operations and compose with ordinary forks. */
class ProjectionCursorTest {

	private static final Keyword VALUE=Keyword.intern("value");
	private static final Keyword OTHER=Keyword.intern("other");

	@Test void atomicOperationsPreserveSiblingsAndUnchangedStorage() {
		AHashMap<Keyword,ACell> initial=Maps.of(VALUE,CVMLong.ONE,OTHER,CVMLong.TWO);
		var root=Root.create(initial);
		AtomicInteger writes=new AtomicInteger();
		var view=ProjectionCursor.create(root,map -> (CVMLong)map.get(VALUE),(map,value) -> {
			writes.incrementAndGet();
			return map.assoc(VALUE,value);
		});
		view.set(CVMLong.ONE);
		assertFalse(view.compareAndSet(CVMLong.ZERO,CVMLong.TWO));
		assertTrue(view.compareAndSet(CVMLong.ONE,CVMLong.ONE));
		assertSame(initial,root.get());
		assertEquals(0,writes.get());

		assertEquals(CVMLong.ONE,view.getAndSet(CVMLong.TWO));
		assertEquals(CVMLong.TWO,view.getAndUpdate(v -> CVMLong.create(v.longValue()+1)));
		assertEquals(CVMLong.create(4),view.updateAndGet(v -> CVMLong.create(v.longValue()+1)));
		assertEquals(CVMLong.create(4),view.getAndAccumulate(CVMLong.ONE,(a,b) -> CVMLong.create(a.longValue()+b.longValue())));
		assertEquals(CVMLong.create(6),view.accumulateAndGet(CVMLong.ONE,(a,b) -> CVMLong.create(a.longValue()+b.longValue())));
		assertEquals(CVMLong.TWO,root.get().get(OTHER));
		view.set((CVMLong)null);
		assertNull(view.get());
		assertEquals(CVMLong.TWO,root.get().get(OTHER));
	}

	@Test void failedProjectionWriteCannotPublishOrAdvanceForkBaseline() {
		var root=Root.create(CVMLong.ZERO);
		AtomicBoolean reject=new AtomicBoolean(true);
		var view=ProjectionCursor.create(root,value -> value,(current,value) -> {
			if (reject.get()) throw new IllegalArgumentException("Write rejected");
			return value;
		});
		var fork=view.fork();
		fork.set(CVMLong.ONE);
		assertThrows(IllegalArgumentException.class,() -> fork.sync((own,other) -> own));
		assertSame(CVMLong.ZERO,root.get());
		assertSame(CVMLong.ONE,fork.get());
		reject.set(false);
		assertSame(CVMLong.ONE,fork.sync((own,other) -> own));
		assertSame(CVMLong.ONE,root.get());
	}

	@Test void forkOfProjectionOverSignedStoragePreservesConcurrentSiblingEdit() {
		var key=AKeyPair.createSeeded(7312);
		AHashMap<Keyword,ACell> initial=Maps.of(VALUE,CVMLong.ZERO,OTHER,CVMLong.ZERO);
		var root=Root.create(key.signData(initial));
		AtomicInteger signatures=new AtomicInteger();
		var signed=SignedCursor.create(root,value -> {
			if (signatures.incrementAndGet()==1) {
				// Force a conflict during preparation, changing a sibling inside the envelope.
				root.set(key.signData(initial.assoc(OTHER,CVMLong.TWO)));
			}
			return key.signData(value);
		});
		var projected=ProjectionCursor.create(signed,map -> (CVMLong)map.get(VALUE),
			(map,value) -> map.assoc(VALUE,value));
		var fork=projected.fork();
		fork.set(CVMLong.ONE);
		assertEquals(0,signatures.get());
		assertEquals(CVMLong.ONE,fork.sync((own,other) -> own));
		assertEquals(CVMLong.ONE,root.get().getValue().get(VALUE));
		assertEquals(CVMLong.TWO,root.get().getValue().get(OTHER));
		assertTrue(root.get().checkSignature());
		assertEquals(2,signatures.get(),"Changed payload must be rebuilt and signed again");
		fork.sync((own,other) -> own);
		assertEquals(2,signatures.get(),"Unchanged publication preserves the envelope");
	}

	@Test void forkAdoptsTheValueProducedByDestinationStamping() {
		var root=Root.<AHashMap<Keyword,ACell>>create(Maps.of(VALUE,CVMLong.ZERO));
		var stamped=StampedCursor.create(root,() -> CVMLong.TWO,(value,time) -> value.assoc(OTHER,time));
		// The identity projection must return the stamped source value, too.
		var view=ProjectionCursor.create(stamped,value -> value,(current,value) -> value);
		var fork=view.fork();
		fork.updateAndGet(value -> value.assoc(VALUE,CVMLong.ONE));
		var published=fork.sync((own,other) -> own);
		assertSame(root.get(),published);
		assertEquals(CVMLong.TWO,fork.get().get(OTHER));
		assertSame(root.get(),fork.sync((own,other) -> own));
	}
}
