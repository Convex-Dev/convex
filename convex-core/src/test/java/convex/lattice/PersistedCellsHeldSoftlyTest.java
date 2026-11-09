package convex.lattice;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import convex.core.crypto.AKeyPair;
import convex.core.data.ABlob;
import convex.core.data.ACell;
import convex.core.data.AHashMap;
import convex.core.data.Blobs;
import convex.core.data.Cells;
import convex.core.data.Hash;
import convex.core.data.Keyword;
import convex.core.data.Maps;
import convex.core.data.Ref;
import convex.core.data.RefSoft;
import convex.core.data.SignedData;
import convex.core.data.prim.CVMLong;
import convex.core.exceptions.StoreException;
import convex.etch.EtchStore;
import convex.lattice.cursor.AForkableCursor;
import convex.lattice.cursor.Cursors;
import convex.lattice.cursor.Persister;
import convex.lattice.cursor.Root;
import convex.lattice.cursor.SignedCursor;
import convex.lattice.cursor.Transactions;

/**
 * Saved data leaves memory only once whoever holds it has switched to the persisted
 * cells, so each test checks what is held at one named stage. References are
 * inspected directly, which says what the collector may reclaim without running it.
 * Races are injected through the callbacks each stage already takes.
 */
public class PersistedCellsHeldSoftlyTest {

	private static final CVMLong GROUP=CVMLong.create(0);
	private static final Keyword SIGNED=Keyword.intern("signed");
	private static final Keyword CHILD=Keyword.intern("child");

	private EtchStore store;

	@BeforeEach void openStore() throws IOException {
		store=EtchStore.createTemp("persisted-cells-held-softly");
	}

	@AfterEach void closeStore() {
		store.close();
	}

	// ===== Data =====

	/** Large enough to be stored under its own hash rather than embedded. */
	private static ABlob blob(long seed) {
		return Blobs.createRandom(new Random(seed),1000);
	}

	/** Nested maps of branch cells, big enough that each map is itself a tree of nodes. */
	private static AHashMap<ACell,ACell> tree() {
		AHashMap<ACell,ACell> root=Maps.empty();
		for (long group=0;group<3;group++) {
			AHashMap<ACell,ACell> entries=Maps.empty();
			for (long i=0;i<40;i++) entries=entries.assoc(CVMLong.create(i),blob(group*100+i));
			root=root.assoc(CVMLong.create(group),entries);
		}
		return root;
	}

	/** Adds a nested entry, rebuilding only the cells on its path. */
	@SuppressWarnings("unchecked")
	private static AHashMap<ACell,ACell> with(AHashMap<ACell,ACell> root,long seed) {
		AHashMap<ACell,ACell> entries=(AHashMap<ACell,ACell>)root.get(GROUP);
		return root.assoc(GROUP,entries.assoc(CVMLong.create(seed),blob(seed)));
	}

	@SuppressWarnings("unchecked")
	private static boolean holds(AHashMap<ACell,ACell> root,long seed) {
		AHashMap<ACell,ACell> entries=(AHashMap<ACell,ACell>)root.get(GROUP);
		return blob(seed).equals(entries.get(CVMLong.create(seed)));
	}

	private LatticeHost<AHashMap<ACell,ACell>> savedHost() {
		var host=LatticeHost.create(tree(),store);
		host.sync();
		return host;
	}

	private static void write(LatticeHost<AHashMap<ACell,ACell>> host,long seed) {
		host.cursor().updateAndGet(root -> with(root,seed));
	}

	// ===== What is held =====

	/** Non-embedded cells below this one reached through strong references alone, loading nothing. */
	private static Set<ACell> strongCells(ACell top) {
		Set<ACell> strong=Collections.newSetFromMap(new IdentityHashMap<>());
		collectStrong(top,strong,Collections.newSetFromMap(new IdentityHashMap<>()));
		return strong;
	}

	private static void collectStrong(ACell cell,Set<ACell> strong,Set<ACell> seen) {
		if (cell==null || !seen.add(cell)) return;
		for (int i=0;i<Cells.refCount(cell);i++) {
			Ref<ACell> ref=Cells.getRef(cell,i);
			if (!ref.isDirect()) continue;
			ACell child=ref.getValue();
			if (child!=null && !ref.isEmbedded()) strong.add(child);
			collectStrong(child,strong,seen);
		}
	}

	/** Every non-embedded reference in the whole tree is soft, bound to this store, persisted and present there. */
	private void assertHeldSoftly(ACell top) {
		assertTrue(checkSoft(top,new HashSet<>())>0,"Nothing to check");
	}

	private int checkSoft(ACell cell,Set<Hash> seen) {
		int checked=0;
		for (int i=0;i<Cells.refCount(cell);i++) {
			Ref<ACell> ref=Cells.getRef(cell,i);
			if (!ref.isEmbedded()) {
				assertInstanceOf(RefSoft.class,ref,"Held strongly");
				assertSame(store,((RefSoft<ACell>)ref).getStore(),"Bound to another store");
				// A reference decoded from the store has no status of its own until read, so ask the store
				Ref<ACell> stored=store.refForHash(ref.getHash());
				assertNotNull(stored,"Not in the store");
				assertTrue(stored.getStatus()>=Ref.PERSISTED,"Not persisted");
				checked++;
				if (!seen.add(ref.getHash())) continue;
			}
			ACell child=ref.getValue();
			if (child!=null) checked+=checkSoft(child,seen);
		}
		return checked;
	}

	/** Whether this cell is the target or leads to it through strong references. */
	private static boolean leadsTo(ACell cell,ACell target) {
		if (target.equals(cell)) return true;
		for (int i=0;i<Cells.refCount(cell);i++) {
			Ref<ACell> ref=Cells.getRef(cell,i);
			if (ref.isDirect() && ref.getValue()!=null && leadsTo(ref.getValue(),target)) return true;
		}
		return false;
	}

	private static <T> T result(Future<T> work) {
		try { return work.get(10,TimeUnit.SECONDS); }
		catch (Exception e) { throw new AssertionError(e); }
	}

	// ===== Components under test =====

	private static class Working extends ALatticeComponent<AHashMap<ACell,ACell>> {
		AHashMap<ACell,ACell> mergedBase;
		AHashMap<ACell,ACell> mergedOwn;

		Working(AForkableCursor<AHashMap<ACell,ACell>> cursor) {
			super(cursor);
		}

		Working(AForkableCursor<AHashMap<ACell,ACell>> cursor,Persister<AHashMap<ACell,ACell>> persister) {
			super(cursor,persister);
		}

		void edit(long seed) {
			cursor().updateAndGet(root -> with(root,seed));
		}

		AHashMap<ACell,ACell> held() {
			return storage().get();
		}

		@Override protected AHashMap<ACell,ACell> mergePublication(
				AHashMap<ACell,ACell> base,AHashMap<ACell,ACell> own,AHashMap<ACell,ACell> other) {
			mergedBase=base;
			mergedOwn=own;
			return LatticeOps.mergeChanges(base,own,other);
		}
	}

	/** A private copy of the root with a child publishing its own subtree through its own fork of the host. */
	private static class Application extends ALatticeApplication<AHashMap<ACell,ACell>> {
		final Working child;

		Application(LatticeHost<AHashMap<ACell,ACell>> host) {
			super(host.cursor().fork(),host::persist);
			AForkableCursor<AHashMap<ACell,ACell>> subtree=host.cursor().path(CHILD);
			child=new Working(subtree.fork(),host::persist);
		}

		void edit(long seed) {
			cursor().updateAndGet(root -> with(root,seed));
		}

		AHashMap<ACell,ACell> held() {
			return storage().get();
		}

		@Override public AHashMap<ACell,ACell> merge(AHashMap<ACell,ACell> incoming) {
			return cursor().get();
		}

		@Override protected void syncChildren() {
			child.sync();
		}

		@Override protected AHashMap<ACell,ACell> mergePublication(
				AHashMap<ACell,ACell> base,AHashMap<ACell,ACell> own,AHashMap<ACell,ACell> other) {
			return LatticeOps.mergeChanges(base,own,other);
		}
	}

	// ===== The checks themselves =====

	@Test void aFreshWriteIsHeldStrongly() {
		AHashMap<ACell,ACell> value=tree();
		assertFalse(strongCells(value).isEmpty());
		assertThrows(AssertionError.class,() -> assertHeldSoftly(value),"The check must reject strong cells");
	}

	// ===== Host save =====

	@Test void aQuietSaveLeavesTheRootHeldSoftly() {
		var host=LatticeHost.create(tree(),store);
		var published=host.sync();
		assertSame(published,host.cursor().get(),"The root holds the persisted value");
		assertHeldSoftly(host.cursor().get());
		assertEquals(tree(),host.cursor().get());
	}

	@Test void aWriteDuringASaveStillLeavesTheRootHeldSoftly() throws IOException {
		var host=LatticeHost.create(tree(),store);
		var snapshot=host.cursor().get();
		var published=host.sync(persisted -> {
			write(host,9000);
			return persisted;
		});
		var root=host.cursor().get();
		assertHeldSoftly(root);
		assertTrue(holds(root,9000),"The write is kept");
		assertEquals(snapshot,published,"The sync published the snapshot it took");
		assertEquals(snapshot,store.getRootData(),"The write is not published until the next sync");
	}

	@Test void aWriteDuringEverySaveNeverLeavesCellsStrong() {
		var host=savedHost();
		for (long cycle=0;cycle<10;cycle++) {
			long racing=9000+cycle;
			write(host,8000+cycle);
			host.sync(persisted -> {
				write(host,racing);
				return persisted;
			});
			assertHeldSoftly(host.cursor().get());
			assertTrue(holds(host.cursor().get(),racing));
		}
	}

	@Test void aSaveWithAWriteLandingDoesNotWaitForAClaim() throws Exception {
		var host=LatticeHost.create(tree(),store);
		AForkableCursor<AHashMap<ACell,ACell>> claimed=host.cursor().path(CVMLong.create(1));
		var during=new AtomicReference<AHashMap<ACell,ACell>>();
		try (var other=Executors.newVirtualThreadPerTaskExecutor()) {
			Transactions.executeExclusively(claimed,draft -> {
				result(other.submit(() -> host.sync(persisted -> {
					write(host,9000);
					return persisted;
				})));
				during.set(host.cursor().get());
			});
		}
		assertHeldSoftly(during.get());
		assertTrue(holds(during.get(),9000));
	}

	@Test void aHostWithNoStoreKeepsTheRootItHolds() {
		var host=LatticeHost.<AHashMap<ACell,ACell>>create(tree(),null);
		var written=new AtomicReference<AHashMap<ACell,ACell>>();
		host.sync(persisted -> {
			written.set(host.cursor().updateAndGet(root -> with(root,9000)));
			return persisted;
		});
		assertSame(written.get(),host.cursor().get());
	}

	@Test void savedDataIsReadableFromAReopenedStore() throws IOException {
		var host=LatticeHost.create(tree(),store);
		write(host,8000);
		var saved=host.checkpoint();
		File file=store.getFile();
		store.close();
		store=EtchStore.create(file);
		AHashMap<ACell,ACell> reopened=store.getRootData();
		assertEquals(saved,reopened);
		assertTrue(holds(reopened,8000));
		assertHeldSoftly(reopened);
	}

	// ===== Forks and persist =====

	@Test void aHostSaveLeavesAForkOnItsOwnCells() {
		var host=LatticeHost.create(tree(),store);
		var fork=host.cursor().fork();
		var held=fork.updateAndGet(root -> with(root,9000));
		host.sync();
		assertSame(held,fork.get());
		assertFalse(strongCells(fork.get()).isEmpty());
	}

	@Test void writesThatNeverStopCannotHoldUpAPersist() throws IOException {
		Root<AHashMap<ACell,ACell>> root=Cursors.root(tree());
		List<Long> written=new ArrayList<>();
		root.persist(value -> {
			assertTrue(written.size()<3,"Still persisting after three attempts");
			long seed=9000+written.size();
			written.add(seed);
			root.updateAndGet(current -> with(current,seed));
			return Cells.persist(value,store);
		});
		assertEquals(List.of(9000L,9001L,9002L),written,"Three attempts, then it stops");
		for (long seed:written) assertTrue(holds(root.get(),seed),"Every write is kept");
	}

	// ===== Component sync =====

	@Test void afterSyncAComponentHoldsItsWorkingStateSoftly() throws IOException {
		var host=savedHost();
		var component=new Working(host.cursor().fork(),host::persist);
		var saved=store.getRootData();
		component.edit(9000);
		assertFalse(strongCells(component.held()).isEmpty(),"An unsynced edit is strong");

		component.sync();

		assertHeldSoftly(component.held());
		assertTrue(holds(component.held(),9000));
		assertTrue(holds(host.cursor().get(),9000),"Published");
		assertEquals(saved,store.getRootData(),"Before the host saves it");
	}

	@Test void afterSyncTheBaselineIsHeldSoftlyToo() {
		var host=savedHost();
		var component=new Working(host.cursor().fork(),host::persist);
		component.edit(9000);
		component.sync();

		component.edit(9001);
		write(host,9002);
		component.sync();

		assertNotNull(component.mergedBase,"Both sides changed");
		assertHeldSoftly(component.mergedBase);
	}

	@Test void aForkTakenBeforeTheFirstSaveLeavesTheHostSoftAfterTheNextSave() {
		var host=LatticeHost.create(tree(),store);
		var component=new Working(host.cursor().fork(),host::persist);
		host.sync();
		assertFalse(strongCells(component.held()).isEmpty(),"The fork still holds what it was given");

		component.edit(9000);
		component.sync();
		assertHeldSoftly(component.held());

		host.sync();
		assertHeldSoftly(host.cursor().get());
		assertTrue(holds(host.cursor().get(),9000));
	}

	@Test void cellsBuiltByAMergeAreHeldSoftlyAfterTheSameSync() {
		var host=savedHost();
		var component=new Working(host.cursor().fork(),host::persist);
		component.edit(9000);
		write(host,9001);

		component.sync();

		assertNotNull(component.mergedOwn,"Both sides changed");
		assertHeldSoftly(component.held());
		assertTrue(holds(component.held(),9000));
		assertTrue(holds(component.held(),9001));
	}

	@Test void anEditDuringThePersistIsKeptAndHeldSoftly() {
		var host=savedHost();
		var component=new AtomicReference<Working>();
		var edited=new AtomicReference<Boolean>(false);
		component.set(new Working(host.cursor().fork(),value -> {
			if (edited.compareAndSet(false,true)) component.get().edit(9001);
			return host.persist(value);
		}));
		component.get().edit(9000);

		component.get().sync();

		assertHeldSoftly(component.get().held());
		assertTrue(holds(component.get().held(),9001),"The edit is kept");
		assertFalse(holds(host.cursor().get(),9001),"The edit came after the publication");
	}

	@Test void anEditAfterSyncIsTheOnlyPathHeldStrongly() {
		var host=savedHost();
		var component=new Working(host.cursor().fork(),host::persist);
		component.edit(9000);
		component.sync();

		component.edit(9001);

		Set<ACell> strong=strongCells(component.held());
		assertFalse(strong.isEmpty());
		ABlob edit=blob(9001);
		assertTrue(strong.stream().anyMatch(edit::equals),"The edit itself is strong");
		for (ACell cell:strong) assertTrue(leadsTo(cell,edit),"Strong but not on the edit's path: "+cell.getClass());
	}

	@Test void aSignedForkHoldsItsValueSoftlyInsideTheSignature() {
		var key=AKeyPair.createSeeded(1234);
		var host=LatticeHost.<AHashMap<ACell,ACell>>create(Maps.empty(),store);
		AForkableCursor<SignedData<AHashMap<ACell,ACell>>> slot=host.cursor().path(SIGNED);
		var signed=SignedCursor.create(slot,key);
		signed.set(tree());
		host.sync();
		var component=new Working(signed.fork(),host::persist);
		component.edit(9000);

		component.sync();
		assertHeldSoftly(component.held());

		host.sync();
		assertHeldSoftly(host.cursor().get());
		@SuppressWarnings("unchecked")
		SignedData<AHashMap<ACell,ACell>> saved=(SignedData<AHashMap<ACell,ACell>>)(ACell)host.cursor().get().get(SIGNED);
		assertTrue(saved.checkSignature());
		assertEquals(component.held(),saved.getValue());
	}

	@Test void anApplicationAndItsChildrenAreHeldSoftlyAfterSyncAndSave() {
		AHashMap<ACell,ACell> start=tree().assoc(CHILD,tree());
		var host=LatticeHost.create(start,store);
		host.sync();
		var application=new Application(host);
		application.edit(9000);
		application.child.edit(9001);

		application.sync();
		assertHeldSoftly(application.held());
		assertHeldSoftly(application.child.held());

		host.sync();
		assertHeldSoftly(host.cursor().get());
		assertTrue(holds(host.cursor().get(),9000));
		assertTrue(holds(application.child.held(),9001));
	}

	@Test void aLongRunningComponentNeverAccumulatesStrongCells() {
		var host=savedHost();
		var component=new Working(host.cursor().fork(),host::persist);
		for (long cycle=0;cycle<10;cycle++) {
			for (long write=0;write<10;write++) component.edit(1000+cycle*100+write);
			component.sync();
			assertHeldSoftly(component.held());
			host.sync();
			assertHeldSoftly(host.cursor().get());
			assertHeldSoftly(component.held());
		}
	}

	@Test void withoutAPersistFunctionSyncLeavesCellsAsTheyAre() {
		var host=savedHost();
		var component=new Working(host.cursor().fork());
		component.edit(9000);

		component.sync();

		assertFalse(strongCells(component.held()).isEmpty());
		assertNull(store.refForHash(blob(9000).getHash()),"Nothing was written to the store");
	}

	@Test void whenPersistingFailsThePublishStandsAndTheErrorIsReported() {
		var host=savedHost();
		var failure=new IOException("store failed");
		var component=new Working(host.cursor().fork(),value -> { throw failure; });
		component.edit(9000);
		var held=component.held();

		var thrown=assertThrows(StoreException.class,component::sync);

		assertSame(failure,thrown.getCause());
		assertTrue(holds(host.cursor().get(),9000),"The publication stands");
		assertEquals(held,component.held());
	}
}
