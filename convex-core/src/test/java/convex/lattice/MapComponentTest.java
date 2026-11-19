package convex.lattice;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.UnaryOperator;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

import convex.core.data.ACell;
import convex.core.data.AHashMap;
import convex.core.data.AString;
import convex.core.data.Blob;
import convex.core.data.Index;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;
import convex.lattice.cursor.AForkableCursor;
import convex.lattice.cursor.MissingValueException;
import convex.lattice.cursor.Root;
import convex.lattice.cursor.Transactions;

class MapComponentTest {
	private static final AString A=Strings.intern("a");
	private static final AString B=Strings.intern("b");

	@Test void unsupportedIndexKeyCannotClearTheCollection() {
		var root=Root.create(Index.<AString,ACell>of(A,CVMLong.ONE,B,CVMLong.TWO));
		var index=new IndexComponent<>(root);
		var before=root.get();
		assertThrows(IllegalArgumentException.class,() -> index.put(null,CVMLong.ONE));
		assertThrows(IllegalArgumentException.class,() -> index.path(null));
		assertNull(index.remove(null));
		assertSame(before,root.get());
		var map=new MapComponent<AString,ACell>(new Root<>());
		map.put(null,CVMLong.ONE);
		assertEquals(CVMLong.ONE,map.get(null),"Hash maps support a nil key");
	}

	private static AMapComponent<AString,ACell,?> component(Root<ACell> root,boolean indexed) {
		return indexed?new IndexComponent<>(root.path()):new MapComponent<>(root.path());
	}

	private static AMapComponent<AString,ACell,?> creationPolicy(Root<ACell> root,boolean indexed,boolean allow) {
		return indexed?new IndexComponent<AString,ACell>(root.path()) {
			@Override protected Index<AString,ACell> createContainer() {
				if (!allow) throw new UnsupportedOperationException("Explicit initialisation required");
				return Index.of(A,CVMLong.ONE,B,CVMLong.TWO);
			}
		}:new MapComponent<AString,ACell>(root.path()) {
			@Override protected AHashMap<AString,ACell> createContainer() {
				if (!allow) throw new UnsupportedOperationException("Explicit initialisation required");
				return Maps.of(A,CVMLong.ONE,B,CVMLong.TWO);
			}
		};
	}

	/** A domain can require explicit initialisation for both direct and child edits. */
	@ParameterizedTest @ValueSource(booleans={false,true})
	void creationCanBeForbiddenWithoutPreventingReadsOrExistingEdits(boolean indexed) {
		var root=new Root<ACell>();
		var map=creationPolicy(root,indexed,false);
		assertNull(map.get());
		assertFalse(map.path(A).exists());
		assertEquals(0,map.size());
		assertFalse(map.containsKey(A));
		assertNull(map.remove(A));
		map.clear();
		assertThrows(UnsupportedOperationException.class,() -> map.path(A).set(null));
		assertThrows(UnsupportedOperationException.class,() -> map.put(A,null));
		assertThrows(UnsupportedOperationException.class,() -> map.path(A).set(CVMLong.ONE));
		assertNull(root.get(),"failed creation must leave storage absent");
		root.set(indexed?Index.none():Maps.empty());
		map.put(A,CVMLong.ONE);
		map.path(B).set(CVMLong.TWO);
		assertEquals(2,map.size());
		map.clear();
		assertTrue(map.isEmpty());
	}

	/** Where the map is absent and cannot be created, an entry view has nowhere to write, whatever the value. */
	@ParameterizedTest @ValueSource(booleans={false,true})
	void entryViewRefusesEveryWriteWhereTheMapCannotBeCreated(boolean indexed) {
		var root=new Root<ACell>();
		var entry=creationPolicy(root,indexed,false).<ACell>entry(A);
		assertThrows(UnsupportedOperationException.class,() -> entry.set(null));
		assertThrows(UnsupportedOperationException.class,() -> entry.compareAndSet(null,null));
		assertThrows(UnsupportedOperationException.class,() -> entry.updateAndGet(value -> value));
		assertThrows(UnsupportedOperationException.class,() -> entry.set(CVMLong.ONE));
		assertNull(root.get());
		ACell empty=indexed?Index.none():Maps.empty();
		root.set(empty);
		entry.set(null);
		assertSame(empty,root.get(),"once the map exists, a write that changes nothing is accepted and creates nothing");
	}

	/** A working copy of such an entry is refused at sync, changed or not, and keeps its edits. */
	@ParameterizedTest @ValueSource(booleans={false,true})
	void forkOfAnEntryIsRefusedAtSyncWhereTheMapCannotBeCreated(boolean indexed) {
		var root=new Root<ACell>();
		var fork=creationPolicy(root,indexed,false).<ACell>entry(A).fork();
		assertThrows(UnsupportedOperationException.class,() -> fork.reconcile((base,own,other) -> own));
		fork.set(CVMLong.TWO);
		assertThrows(UnsupportedOperationException.class,() -> fork.reconcile((base,own,other) -> own));
		assertEquals(CVMLong.TWO,fork.get(),"a refused sync keeps the working value");
		assertNull(root.get());
		root.set(indexed?Index.none():Maps.empty());
		assertEquals(CVMLong.TWO,fork.reconcile((base,own,other) -> own));
		assertEquals(CVMLong.TWO,creationPolicy(root,indexed,false).get(A));
	}

	/**
	 * A transaction that changed nothing, whose record is deleted while it runs, is refused
	 * through a view inside the record whose map cannot be created, as on a plain path. The
	 * record's own entry view still accepts it: its map, the records map, still exists.
	 */
	@Test void deletedRecordRefusesAnUnchangedCommitInsideItButNotAtTheRecord() {
		for (boolean inside:new boolean[] {true,false}) {
			Root<ACell> root=Root.create(Maps.of(A,Maps.of(B,CVMLong.ONE)));
			AForkableCursor<AHashMap<AString,ACell>> record=component(root,false).entry(A);
			AForkableCursor<ACell> subject=inside?new MapComponent<AString,ACell>(record) {
				@Override protected AHashMap<AString,ACell> createContainer() { throw new IllegalStateException("Record missing"); }
			}.entry(B):component(root,false).entry(A);
			int[] runs={0};
			Runnable transaction=() -> Transactions.transact(subject,draft -> {
				if (runs[0]++==0) root.set(Maps.empty());
				return draft.get();
			});
			if (inside) assertThrows(IllegalStateException.class,transaction::run);
			else transaction.run();
			assertEquals(1,runs[0]);
			assertEquals(Maps.empty(),root.get(),"the deleted record is not written back");
		}
	}

	/** Creation may supply initial entries, even the same entry being written. */
	@ParameterizedTest @ValueSource(booleans={false,true})
	void directAndChildWritesPreserveInitialEntries(boolean indexed) {
		for (boolean child:new boolean[] {false,true}) {
			var root=new Root<ACell>();
			var map=creationPolicy(root,indexed,true);
			if (child) map.path(A).set(CVMLong.ONE);
			else assertNull(map.put(A,CVMLong.ONE),"previous value comes from storage, not initial entries");
			assertEquals(CVMLong.ONE,map.get(A));
			assertEquals(CVMLong.TWO,map.get(B));
			assertEquals(indexed,root.get() instanceof Index<?,?>);
			root.set(CVMLong.ONE);
			assertThrows(IllegalStateException.class,() -> map.put(A,CVMLong.ONE));
			assertThrows(IllegalStateException.class,() -> map.path(A).set(CVMLong.ONE));
			assertSame(CVMLong.ONE,root.get(),"creation must not replace incompatible storage");
		}
	}

	/** A losing creation attempt must use the winning container on its retry. */
	@ParameterizedTest @ValueSource(booleans={false,true})
	void concurrentCreationPreservesTheWinningContents(boolean indexed) {
		for (boolean child:new boolean[] {false,true}) {
			var attempts=new AtomicInteger();
			ACell winner=indexed?Index.of(B,CVMLong.create(99)):Maps.of(B,CVMLong.create(99));
			var root=new Root<ACell>() {
				@Override public ACell getAndUpdate(UnaryOperator<ACell> update) {
					return super.getAndUpdate(current -> {
						ACell candidate=update.apply(current);
						if (attempts.incrementAndGet()==1) set(winner);
						return candidate;
					});
				}
			};
			var map=creationPolicy(root,indexed,true);
			if (child) assertNull(map.path(A).getAndSet(CVMLong.ONE));
			else assertNull(map.put(A,CVMLong.ONE));
			assertEquals(CVMLong.ONE,map.get(A));
			assertEquals(CVMLong.create(99),map.get(B));
			assertTrue(attempts.get()>1,"the test must actually exercise a retry");
		}
	}

	@ParameterizedTest @ValueSource(booleans={false,true})
	void absentReadsAndNoOpEditsDoNotCreateStorage(boolean indexed) {
		var root=new Root<ACell>();
		var map=component(root,indexed);
		assertNull(map.get());
		assertTrue(map.isEmpty());
		assertEquals(0,map.size());
		assertNull(map.get(A));
		assertNull(map.remove(A));
		map.clear();
		map.path(A).set(null);
		assertNull(root.get());
		// Nil is a legitimate stored entry, distinct from an absent key.
		map.put(A,null);
		assertTrue(map.containsKey(A));
		assertEquals(1,map.size());
		assertEquals(indexed,root.get() instanceof Index<?,?>);
		map.remove(A);
		assertFalse(map.containsKey(A));
	}

	/** An entry reads as a path does: a missing key is absent and a stored nil is present. */
	@ParameterizedTest @ValueSource(booleans={false,true})
	void entryViewsReadMissingKeysAsAbsentAndStoredNilAsNil(boolean indexed) {
		var root=new Root<ACell>();
		var map=component(root,indexed);
		AForkableCursor<ACell> entry=map.path(A);
		assertFalse(entry.exists(),"absent storage holds no entries");
		assertThrows(MissingValueException.class,entry::get);
		assertSame(CVMLong.ZERO,entry.getOrDefault(CVMLong.ZERO));
		map.put(B,CVMLong.TWO);
		assertFalse(entry.exists());
		assertFalse(root.path(A).exists(),"the same as a plain path");
		assertThrows(MissingValueException.class,entry::get);
		assertSame(CVMLong.ZERO,entry.getOrDefault(CVMLong.ZERO));
		assertFalse(new MapComponent<AString,ACell>(map.path(A)).exists(),"a component bound to a missing entry is absent");
		map.put(A,null);
		assertTrue(entry.exists());
		assertNull(entry.get());
		assertNull(entry.getOrDefault(CVMLong.ZERO),"a stored nil does not select the fallback");
		root.set(CVMLong.ONE);
		assertThrows(IllegalStateException.class,entry::exists,"incompatible storage is rejected, not read as absent");
	}

	@ParameterizedTest @ValueSource(booleans={false,true})
	void equalPutLeavesStorageUnchanged(boolean indexed) {
		var root=new Root<ACell>();
		var map=component(root,indexed);
		map.put(A,Blob.wrap(new byte[] {1,2,3}));
		var before=root.get();
		assertEquals(Blob.wrap(new byte[] {1,2,3}),map.put(A,Blob.wrap(new byte[] {1,2,3}))); // equal, distinct instance
		assertSame(before,root.get());
	}

	@ParameterizedTest @ValueSource(booleans={false,true})
	void entryViewsShareEditsAndPreserveSiblingsAndSnapshots(boolean indexed) {
		var root=new Root<ACell>();
		var map=component(root,indexed);
		var child=map.path(A);
		var opaque=Vectors.of("future",Maps.of("version",2));
		map.put(B,opaque);
		child.set(CVMLong.ONE);
		var snapshot=map.get();
		assertEquals(2,map.size());
		assertEquals(CVMLong.ONE,map.put(A,CVMLong.TWO));
		assertEquals(CVMLong.TWO,child.get());
		assertEquals(CVMLong.ONE,snapshot.get(A));
		assertSame(opaque,map.get(B));
		var unchanged=root.get();
		map.put(A,CVMLong.TWO);
		assertSame(unchanged,root.get());
		assertSame(unchanged,map.sync());
		assertEquals(CVMLong.TWO,map.remove(A));
		assertFalse(child.exists());
		assertSame(opaque,map.get(B));
		map.clear();
		assertTrue(map.isEmpty());
		assertEquals(indexed,root.get() instanceof Index<?,?>);
	}

	@ParameterizedTest @ValueSource(booleans={false,true})
	void incompatibleStorageIsRejectedWithoutChangingIt(boolean indexed) {
		var root=Root.<ACell>create(CVMLong.ONE);
		var map=component(root,indexed);
		assertThrows(IllegalStateException.class,map::get);
		assertThrows(IllegalStateException.class,() -> map.put(A,CVMLong.TWO));
		assertThrows(IllegalStateException.class,() -> map.remove(A));
		assertThrows(IllegalStateException.class,map::clear);
		assertThrows(IllegalStateException.class,() -> map.path(A).set(CVMLong.TWO));
		assertSame(CVMLong.ONE,root.get());
		root.set(indexed?Maps.empty():Index.none());
		assertThrows(IllegalStateException.class,() -> map.path(A).set(CVMLong.TWO));
	}
}
