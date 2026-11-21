package convex.lattice.app;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;

import convex.core.crypto.AKeyPair;
import convex.core.data.ACell;
import convex.core.data.AHashMap;
import convex.core.data.Index;
import convex.core.data.Keyword;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.prim.CVMLong;
import convex.lattice.ALocalComponent;
import convex.lattice.Timestamped;
import convex.lattice.TransactionalComponent;
import convex.lattice.cursor.AForkableCursor;
import convex.lattice.cursor.Root;
import convex.test.lattice.ComponentChecks;

/** A third-party domain can bind local records to forks and transaction drafts. */
class LocalComponentRebindTest {

	private static final Keyword SERVICE=Keyword.intern("notes");
	private static final Keyword VALUE=Keyword.intern("value");
	private static final AKeyPair KEY=AKeyPair.createSeeded(7532);

	private static final class Notes extends ALocalComponent
			implements TransactionalComponent<AHashMap<Keyword,ACell>,Notes> {
		private final Supplier<CVMLong> clock;

		Notes(AForkableCursor<Index<Keyword,ACell>> root,Supplier<CVMLong> clock,AtomicInteger signatures) {
			super(root,SERVICE,KEY.getAccountKey(),value -> {
				signatures.incrementAndGet();
				return KEY.signData(value);
			},clock,null);
			this.clock=clock;
		}

		Notes(AForkableCursor<AHashMap<Keyword,ACell>> cursor,Supplier<CVMLong> clock) {
			super(cursor,clock,null);
			this.clock=clock;
		}

		@Override public Notes rebind(AForkableCursor<AHashMap<Keyword,ACell>> cursor) {
			return new Notes(cursor,clock);
		}

		@Override public Notes fork() { return rebind(storage.fork()); }

		void put(long value) { cursor().assoc(VALUE,CVMLong.create(value)); }

		/** The storage this component is bound to, beneath its stamping view */
		AForkableCursor<AHashMap<Keyword,ACell>> bound() { return storage(); }
	}

	@Test void notesKeepTheComponentMergeRules() {
		var notes=new Notes(new Root<AHashMap<Keyword,ACell>>(),() -> CVMLong.create(5000));
		assertNull(ComponentChecks.checkCommutingDeliveries(notes,
			Maps.of(Timestamped.TIMESTAMP,CVMLong.create(1000),VALUE,Strings.create("first")),
			Maps.of(Timestamped.TIMESTAMP,CVMLong.create(2000),VALUE,Strings.create("second"))));
	}

	@Test void nestedForkReinstallsStampingAndStopsAtItsDestination() {
		var root=Root.create(Index.<Keyword,ACell>none());
		var signatures=new AtomicInteger();
		var time=new AtomicLong(10);
		var notes=new Notes(root,() -> CVMLong.create(time.get()),signatures);
		var supplied=notes.bound().fork();
		var draft=notes.rebind(supplied);
		assertSame(supplied,draft.bound(),"Rebinding must not add another fork");
		assertNull(draft.bound().get(),"Construction must not materialise a record");
		draft.put(1);
		assertEquals(CVMLong.create(10),draft.value().get(Timestamped.TIMESTAMP));
		assertNull(notes.bound().get());
		draft.sync();
		assertEquals(draft.value(),notes.value());
		assertTrue(root.get().isEmpty());
		assertEquals(0,signatures.get());
		notes.sync();
		assertEquals(1,signatures.get());
		var published=root.get();
		var nested=draft.fork();
		time.set(20);
		nested.put(2);
		nested.sync();
		assertEquals(CVMLong.create(20),draft.value().get(Timestamped.TIMESTAMP));
		assertEquals(CVMLong.ONE,notes.value().get(VALUE));
		assertSame(published,root.get());
	}

	@Test void transactionsCommitToWorkingStorageAndDiscardFailedEdits() {
		var root=Root.create(Index.<Keyword,ACell>none());
		var signatures=new AtomicInteger();
		var time=new AtomicLong(10);
		var notes=new Notes(root,() -> CVMLong.create(time.get()),signatures);
		notes.execute(draft -> {
			draft.put(1);
			assertNull(notes.bound().get());
		});
		assertEquals(CVMLong.ONE,notes.value().get(VALUE));
		assertEquals(CVMLong.create(10),notes.value().get(Timestamped.TIMESTAMP));
		assertTrue(root.get().isEmpty());
		assertEquals(0,signatures.get());
		var before=notes.value();
		time.set(20);
		assertThrows(IllegalStateException.class,() -> notes.execute(draft -> {
			draft.put(2);
			throw new IllegalStateException("Discard draft");
		}));
		assertSame(before,notes.value());
		notes.exclusive().execute(draft -> draft.put(3));
		assertEquals(CVMLong.create(3),notes.value().get(VALUE));
		assertEquals(CVMLong.create(20),notes.value().get(Timestamped.TIMESTAMP));
		assertEquals(0,signatures.get());
		notes.sync();
		assertEquals(1,signatures.get());
	}
}
