package convex.lattice;

import static convex.test.HeldCells.assertHeldSoftly;
import static convex.test.HeldCells.assertOnlyStrongAround;
import static convex.test.HeldCells.strongCells;
import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import convex.core.crypto.AKeyPair;
import convex.core.data.ABlob;
import convex.core.data.ACell;
import convex.core.data.AHashMap;
import convex.core.data.Blobs;
import convex.core.data.Cells;
import convex.core.data.Keyword;
import convex.core.data.Maps;
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

	private static <T> T result(Future<T> work) {
		try { return work.get(10,TimeUnit.SECONDS); }
		catch (Exception e) { throw new AssertionError(e); }
	}

	// ===== Cursors under test =====

	private AHashMap<ACell,ACell> save(AHashMap<ACell,ACell> value) throws IOException {
		return Cells.persist(value,store);
	}

	private void persist(AForkableCursor<AHashMap<ACell,ACell>> cursor) {
		try { cursor.persist(this::save); }
		catch (IOException e) { throw new UncheckedIOException(e); }
	}

	/** A root holding the saved cells of {@link #tree()}. */
	private Root<AHashMap<ACell,ACell>> savedRoot() {
		Root<AHashMap<ACell,ACell>> root=Cursors.root(tree());
		persist(root);
		return root;
	}

	/** Runs the action the first time only, since the callbacks it is injected into may repeat. */
	private static Runnable once(Runnable action) {
		var done=new AtomicBoolean();
		return () -> { if (done.compareAndSet(false,true)) action.run(); };
	}

	private static final Runnable NOTHING=() -> {};

	/** One way of making a local edit, with a point part-way through at which something else can land. */
	private interface EditAction {
		void apply(AForkableCursor<AHashMap<ACell,ACell>> cursor,long seed,Runnable during);
	}

	private record Edit(String name,EditAction action) {
		void apply(AForkableCursor<AHashMap<ACell,ACell>> cursor,long seed,Runnable during) {
			action.apply(cursor,seed,during);
		}
		@Override public String toString() { return name; }
	}

	/** Edits made by a function the cursor runs, and runs again on the cells it then holds. */
	static Stream<Edit> updateFunctions() {
		return Stream.of(
			new Edit("update function",(cursor,seed,during) -> cursor.updateAndGet(root -> {
				during.run();
				return with(root,seed);
			})),
			new Edit("update through a path",(cursor,seed,during) -> cursor.<AHashMap<ACell,ACell>>path(GROUP).updateAndGet(entries -> {
				during.run();
				return entries.assoc(CVMLong.create(seed),blob(seed));
			})));
	}

	/** Every edit, including those that hand the cursor a finished value built from the cells they read. */
	static Stream<Edit> edits() {
		return Stream.concat(updateFunctions(),Stream.of(
			new Edit("compare and set",(cursor,seed,during) -> {
				var read=cursor.get();
				during.run();
				assertTrue(cursor.compareAndSet(read,with(read,seed)),"The cursor still holds the value that was read");
			}),
			new Edit("transaction",(cursor,seed,during) -> Transactions.execute(cursor,draft -> {
				during.run();
				draft.updateAndGet(root -> with(root,seed));
			})),
			new Edit("exclusive transaction",(cursor,seed,during) -> Transactions.executeExclusively(cursor,draft -> {
				during.run();
				draft.updateAndGet(root -> with(root,seed));
			}))));
	}

	/** One way of writing a whole value. */
	private record Write(String name,BiConsumer<AForkableCursor<AHashMap<ACell,ACell>>,AHashMap<ACell,ACell>> action) {
		@Override public String toString() { return name; }
	}

	static Stream<Write> writes() {
		return Stream.of(
			new Write("set",(cursor,value) -> cursor.set(value)),
			new Write("getAndSet",(cursor,value) -> cursor.getAndSet(value)),
			new Write("update function",(cursor,value) -> cursor.updateAndGet(current -> value)),
			new Write("compare and set",(cursor,value) -> assertTrue(cursor.compareAndSet(cursor.get(),value))),
			new Write("transaction",(cursor,value) -> Transactions.execute(cursor,draft -> draft.set(value))),
			new Write("exclusive transaction",(cursor,value) -> Transactions.executeExclusively(cursor,draft -> draft.set(value))));
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
		assertThrows(AssertionError.class,() -> assertHeldSoftly(store,value),"The check must reject strong cells");
	}

	// ===== Host save =====

	@Test void aQuietSaveLeavesTheRootHeldSoftly() {
		var host=LatticeHost.create(tree(),store);
		var published=host.sync();
		assertSame(published,host.cursor().get(),"The root holds the persisted value");
		assertHeldSoftly(store,host.cursor().get());
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
		assertHeldSoftly(store,root);
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
			assertHeldSoftly(store,host.cursor().get());
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
		assertHeldSoftly(store,during.get());
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
		assertHeldSoftly(store,reopened);
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

		assertHeldSoftly(store,component.held());
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
		assertHeldSoftly(store,component.mergedBase);
	}

	@Test void aForkTakenBeforeTheFirstSaveLeavesTheHostSoftAfterTheNextSave() {
		var host=LatticeHost.create(tree(),store);
		var component=new Working(host.cursor().fork(),host::persist);
		host.sync();
		assertFalse(strongCells(component.held()).isEmpty(),"The fork still holds what it was given");

		component.edit(9000);
		component.sync();
		assertHeldSoftly(store,component.held());

		host.sync();
		assertHeldSoftly(store,host.cursor().get());
		assertTrue(holds(host.cursor().get(),9000));
	}

	@Test void cellsBuiltByAMergeAreHeldSoftlyAfterTheSameSync() {
		var host=savedHost();
		var component=new Working(host.cursor().fork(),host::persist);
		component.edit(9000);
		write(host,9001);

		component.sync();

		assertNotNull(component.mergedOwn,"Both sides changed");
		assertHeldSoftly(store,component.held());
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

		assertHeldSoftly(store,component.get().held());
		assertTrue(holds(component.get().held(),9001),"The edit is kept");
		assertFalse(holds(host.cursor().get(),9001),"The edit came after the publication");
	}

	@Test void anEditAfterSyncIsTheOnlyPathHeldStrongly() {
		var host=savedHost();
		var component=new Working(host.cursor().fork(),host::persist);
		component.edit(9000);
		component.sync();

		component.edit(9001);

		assertOnlyStrongAround(component.held(),blob(9001));
	}

	@Test void aSignedForkHoldsItsValueSoftlyInsideTheSignature() {
		var key=AKeyPair.createSeeded(1234);
		var host=LatticeHost.<AHashMap<ACell,ACell>>create(Maps.empty(),store);
		AForkableCursor<SignedData<AHashMap<ACell,ACell>>> slot=new MapComponent<>(host.cursor()).path(SIGNED);
		var signed=SignedCursor.create(slot,key);
		signed.set(tree());
		host.sync();
		var component=new Working(signed.fork(),host::persist);
		component.edit(9000);

		component.sync();
		assertHeldSoftly(store,component.held());

		host.sync();
		assertHeldSoftly(store,host.cursor().get());
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
		assertHeldSoftly(store,application.held());
		assertHeldSoftly(store,application.child.held());

		host.sync();
		assertHeldSoftly(store,host.cursor().get());
		assertTrue(holds(host.cursor().get(),9000));
		assertTrue(holds(application.child.held(),9001));
	}

	@Test void aLongRunningComponentNeverAccumulatesStrongCells() {
		var host=savedHost();
		var component=new Working(host.cursor().fork(),host::persist);
		for (long cycle=0;cycle<10;cycle++) {
			for (long write=0;write<10;write++) component.edit(1000+cycle*100+write);
			component.sync();
			assertHeldSoftly(store,component.held());
			host.sync();
			assertHeldSoftly(store,host.cursor().get());
			assertHeldSoftly(store,component.held());
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

	// ===== Persist: the holder switches to the saved cells =====

	@Test void persistingARootSwitchesItToTheSavedCells() throws IOException {
		Root<AHashMap<ACell,ACell>> root=Cursors.root(tree());
		var before=root.get();

		var saved=root.persist(this::save);

		assertSame(saved,root.get());
		assertNotSame(before,root.get(),"The saved cells are other objects");
		assertEquals(before,root.get(),"With the same value");
		assertHeldSoftly(store,root.get());
	}

	@Test void persistingAForkSwitchesTheForkAndLeavesItsDestination() {
		Root<AHashMap<ACell,ACell>> root=Cursors.root(tree());
		var destination=root.get();
		var fork=root.fork();
		fork.updateAndGet(value -> with(value,9000));

		persist(fork);

		assertHeldSoftly(store,fork.get());
		assertTrue(holds(fork.get(),9000));
		assertSame(destination,root.get());
	}

	// ===== Local edits to saved data =====

	@ParameterizedTest(name="{0}")
	@MethodSource("edits")
	void anEditToSavedDataLeavesOnlyItsOwnPathStrong(Edit edit) {
		var root=savedRoot();

		edit.apply(root,9000,NOTHING);

		assertTrue(holds(root.get(),9000));
		assertOnlyStrongAround(root.get(),blob(9000));
	}

	@ParameterizedTest(name="{0}")
	@MethodSource("writes")
	void writingAnEqualValueToASavedRootKeepsTheSavedCells(Write write) {
		var root=savedRoot();
		var saved=root.get();

		write.action.accept(root,tree());

		assertSame(saved,root.get());
	}

	@ParameterizedTest(name="{0}")
	@MethodSource("writes")
	void writingAnEqualValueToAForkOfSavedDataKeepsTheSavedCells(Write write) {
		var fork=savedRoot().fork();
		var saved=fork.get();

		write.action.accept(fork,tree());

		assertSame(saved,fork.get());
	}

	// ===== An edit and a persist at the same moment =====

	@ParameterizedTest(name="{0}")
	@MethodSource("edits")
	void anEditLandingDuringAPersistIsKeptAndHeldSoftly(Edit edit) throws IOException {
		Root<AHashMap<ACell,ACell>> root=Cursors.root(tree());
		Runnable landing=once(() -> edit.apply(root,9000,NOTHING));

		root.persist(value -> {
			landing.run();
			return save(value);
		});

		assertTrue(holds(root.get(),9000),"The edit is kept");
		assertHeldSoftly(store,root.get());
	}

	@ParameterizedTest(name="{0}")
	@MethodSource("updateFunctions")
	void aPersistLandingDuringAnUpdateFunctionLeavesOnlyTheEditStrong(Edit edit) {
		Root<AHashMap<ACell,ACell>> root=Cursors.root(tree());

		edit.apply(root,9000,once(() -> persist(root)));

		assertTrue(holds(root.get(),9000),"The edit is kept");
		assertOnlyStrongAround(root.get(),blob(9000));
	}

	/**
	 * A finished value, from a compare-and-set or a transaction, is built on the cells
	 * read before the persist and goes in as it is, so those cells are held again
	 * until the next persist.
	 */
	@ParameterizedTest(name="{0}")
	@MethodSource("edits")
	void afterAPersistLandsDuringAnEditTheNextPersistLeavesEverythingSoft(Edit edit) {
		Root<AHashMap<ACell,ACell>> root=Cursors.root(tree());
		edit.apply(root,9000,once(() -> persist(root)));

		persist(root);

		assertTrue(holds(root.get(),9000),"The edit is kept");
		assertHeldSoftly(store,root.get());
	}

	/** One way of handing a view a finished value for its own place. */
	private record ViewWrite(String name,BiConsumer<AForkableCursor<ABlob>,ABlob> action) {
		@Override public String toString() { return name; }
	}

	static Stream<ViewWrite> finishedValuesThroughAView() {
		return Stream.of(
			new ViewWrite("compare and set",(view,value) -> assertTrue(view.compareAndSet(view.get(),value))),
			new ViewWrite("transaction",(view,value) -> Transactions.execute(view,draft -> draft.set(value))),
			new ViewWrite("exclusive transaction",(view,value) -> Transactions.executeExclusively(view,draft -> draft.set(value))));
	}

	/**
	 * A view holds no cells: the source around its value is built as the write lands,
	 * on the cells held then. So a persist landing after the view has read its source
	 * brings none of the saved cells back, whichever way the value was handed over.
	 */
	@ParameterizedTest(name="{0}")
	@MethodSource("finishedValuesThroughAView")
	void aPersistLandingAsAViewWritesLeavesOnlyTheNewValueStrong(ViewWrite write) {
		Runnable[] landing={NOTHING};
		Root<AHashMap<ACell,ACell>> root=new Root<>(tree()) {
			@Override protected boolean install(AHashMap<ACell,ACell> held,AHashMap<ACell,ACell> next) {
				landing[0].run();
				return super.install(held,next);
			}
		};
		AForkableCursor<ABlob> view=root.path(GROUP,CVMLong.create(5));
		landing[0]=once(() -> persist(root));

		write.action.accept(view,blob(9000));

		assertEquals(blob(9000),view.get());
		assertOnlyStrongAround(root.get(),blob(9000));
	}

	@Test void aPersistLandingDuringATransactionThatChangesNothingKeepsTheSavedCells() {
		Root<AHashMap<ACell,ACell>> root=Cursors.root(tree());

		Transactions.execute(root,draft -> {
			persist(root);
			draft.set(tree());
		});

		assertHeldSoftly(store,root.get());
	}

	@Test void aPersistLandingDuringAnExclusiveTransactionThatChangesNothingKeepsTheSavedCells() {
		Root<AHashMap<ACell,ACell>> root=Cursors.root(tree());

		Transactions.executeExclusively(root,draft -> {
			persist(root);
			draft.set(tree());
		});

		assertHeldSoftly(store,root.get());
	}
}
