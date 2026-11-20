package convex.lattice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import convex.core.data.ABlob;
import convex.core.data.ACell;
import convex.core.data.ASet;
import convex.core.data.Blob;
import convex.core.data.Blobs;
import convex.core.data.Cells;
import convex.core.data.Index;
import convex.core.data.Keyword;
import convex.core.data.RefDirect;
import convex.core.data.RefSoft;
import convex.core.data.Sets;
import convex.core.store.MemoryStore;
import convex.core.store.Persister;
import convex.lattice.cursor.DefaultCursor;
import convex.lattice.cursor.AForkableCursor;
import convex.lattice.cursor.Cursors;
import convex.lattice.cursor.Root;
import convex.test.Persisters;
import convex.test.Samples;

public class ALatticeComponentTest {

	/** A component is a cursor plus domain operations; nothing else is inherited. */
	private static class TestComponent extends ALatticeComponent<ASet<ACell>> {

		TestComponent(AForkableCursor<ASet<ACell>> cursor) {
			super(cursor);
		}

		TestComponent(AForkableCursor<ASet<ACell>> cursor,Persister persister) {
			super(cursor,persister);
		}

		public TestComponent fork() {
			return rebind(storage.fork());
		}

		@Override public TestComponent rebind(AForkableCursor<ASet<ACell>> cursor) { return new TestComponent(cursor); }

		@Override
		protected ASet<ACell> reconcile(ASet<ACell> own,ASet<ACell> other) {
			return own.includeAll(other);
		}

		@Override
		public ASet<ACell> merge(ASet<ACell> incoming) {
			return cursor().updateAndGet(own -> own.includeAll(incoming));
		}

		void add(ACell value) {
			cursor().updateAndGet(set->set.include(value));
		}
	}

	/** An application merging a set by union, staged over a host or live in a cursor. */
	private static class SetApplication extends ALatticeApplication<ASet<ACell>> {

		SetApplication(LatticeHost<ASet<ACell>> host) {
			super(host.cursor().fork(),host::persist);
		}

		SetApplication(AForkableCursor<ASet<ACell>> cursor) {
			super(cursor);
		}

		@Override
		public ASet<ACell> merge(ASet<ACell> incoming) {
			return cursor().updateAndGet(own -> own.includeAll(incoming));
		}
	}

	@Test
	public void testStandaloneComponentOverCursor() {
		TestComponent component=new TestComponent(Cursors.root(Sets.<ACell>empty()));
		Blob value=Blob.fromHex("cafebabe");
		component.add(value);
		assertTrue(component.cursor().get().contains(value));
	}

	@Test
	public void testForkPublishesOnlyThroughComponentSync() throws Exception {
		MemoryStore store=new MemoryStore();
		var host=LatticeHost.<ASet<ACell>>create(Sets.empty(),store);
		TestComponent live=new TestComponent(host.cursor());
		TestComponent fork=live.fork();
		Blob value=Blob.fromHex("cafebabe");
		fork.add(value);

		assertTrue(fork.cursor().get().contains(value));
		assertFalse(host.cursor().get().contains(value));

		new TestComponent(fork.cursor()).sync();
		assertTrue(host.cursor().get().contains(value),"Wrappers share the bound fork publication boundary");
		fork.sync();
		assertTrue(host.cursor().get().contains(value));
		assertNull(store.getRootData(),"Merging a fork into the live cursor must not publish the root");
	}

	/** Component-defined reconciliation combines concurrent edits without any ALattice implementation. */
	@Test
	public void independentDraftsReconcileAndRepeatedSyncIsInert() {
		var live=new TestComponent(Cursors.root(Sets.empty()));
		var first=live.fork();
		var second=live.fork();
		var a=Blob.fromHex("aa");
		var b=Blob.fromHex("bb");
		first.add(a);
		second.add(b);
		first.sync();
		second.sync();
		assertEquals(Sets.of(a,b),live.cursor().get());
		var published=live.cursor().get();
		assertSame(published,second.sync());
		assertSame(published,live.cursor().get());
	}

	/** Storage writes belong to the destination's update policy, not the syncing component. */
	@Test
	public void syncUsesTheDestinationsStoragePolicy() throws Exception {
		try (MemoryStore store=new MemoryStore()) {
			var failing=new AtomicBoolean(true);
			var destination=new Root<ASet<ACell>>(Sets.empty()) {
				@Override protected boolean install(ASet<ACell> held,ASet<ACell> next) {
					try {
						if (failing.get()) throw new IOException("Destination storage unavailable");
						return super.install(held,Cells.persist(next,store));
					} catch (IOException e) {
						throw new UncheckedIOException(e);
					}
				}
			};
			var component=new TestComponent(destination.fork(),Persisters.after(value -> {
				throw new AssertionError("Sync must not call the component's persister");
			},Persisters.UNCHANGED));
			ABlob blob=Blobs.createRandom(new Random(8765),1000);
			component.add(blob);
			assertThrows(UncheckedIOException.class,component::sync);
			assertTrue(destination.get().isEmpty());
			assertEquals(Sets.of(blob),component.value());
			failing.set(false);
			assertEquals(Sets.of(blob),component.sync());
			assertEquals(component.value(),destination.get());
			assertNotNull(store.refForHash(blob.getHash()));
			assertNull(store.getRootData(),"Destination policy stored cells without retaining a root");
		}
	}

	/** Persisting writes working state to the store and keeps it in place, leaving the root alone. */
	@Test
	public void persistKeepsWorkingStateInTheStoreWithoutPublishing() throws Exception {
		MemoryStore store=new MemoryStore();
		var host=LatticeHost.<ASet<ACell>>create(Sets.empty(),store);
		var root=host.cursor().get();
		var component=new TestComponent(host.cursor().fork(),host::persist);
		ABlob blob=Blobs.createRandom(new Random(1234),1000);
		component.add(blob);

		var persisted=component.persist();
		assertEquals(Sets.of(blob),persisted);
		assertSame(persisted,component.storage().get());
		assertNotNull(store.refForHash(blob.getHash()));
		assertSame(root,host.cursor().get());
		assertNull(store.getRootData());
	}

	/** An edit landing while persisting is persisted too: the atomic update repeats on the edited value. */
	@Test
	public void persistIsAtomicWithAnEditMadeMeanwhile() throws Exception {
		var host=LatticeHost.<ASet<ACell>>create(Sets.empty(),new MemoryStore());
		var a=Blob.fromHex("aa");
		var b=Blob.fromHex("bb");
		var component=new AtomicReference<TestComponent>();
		var edited=new AtomicBoolean();
		component.set(new TestComponent(host.cursor().fork(),Persisters.after(value -> {
			if (edited.compareAndSet(false,true)) component.get().add(b);
		},host::persist)));
		component.get().add(a);
		var persisted=component.get().persist();
		assertEquals(Sets.of(a,b),persisted);
		assertSame(persisted,component.get().storage().get());
	}

	/** With an Etch store, persisted working state refers to its stored cells softly, so memory can release them. */
	@Test
	public void persistedWorkingStateRefersToStoredCellsSoftly() throws Exception {
		var host=LatticeHost.<ASet<ACell>>create(Sets.empty(),Samples.TEST_STORE);
		var component=new TestComponent(host.cursor().fork(),host::persist);
		component.add(Blobs.createRandom(new Random(4321),1000));
		assertInstanceOf(RefDirect.class,component.storage().get().getRef(0));
		component.persist();
		assertInstanceOf(RefSoft.class,component.storage().get().getRef(0));
	}

	/** Only a component given a function persists itself; others are persisted with their parent. */
	@Test
	public void persistNeedsAFunctionOrAHost() throws Exception {
		var host=LatticeHost.<ASet<ACell>>create(Sets.empty(),new MemoryStore());
		assertThrows(UnsupportedOperationException.class,() -> new TestComponent(host.cursor()).persist());
		assertThrows(UnsupportedOperationException.class,() -> new SetApplication(host.cursor()).persist());

		var application=new SetApplication(host);
		ABlob blob=Blobs.createRandom(new Random(5678),1000);
		application.cursor().update(set -> set.include(blob));
		assertEquals(Sets.of(blob),application.persist());
		assertNotNull(host.store().refForHash(blob.getHash()));
		assertTrue(host.cursor().get().isEmpty());
	}

	/** Region routing depends only on the child's behaviour, not a parallel lattice tree. */
	@Test
	public void regionsRouteToPlainComponentsAndPreserveUnknownState() {
		var key=Keyword.intern("values");
		var unrelated=Keyword.intern("unrelated");
		var marker=Blob.fromHex("abcd");
		var host=LatticeHost.create(Index.<Keyword,ACell>none().assoc(unrelated,marker),null);
		var regions=new Regions(host.cursor());
		regions.create(key,(AForkableCursor<ASet<ACell>> region) -> new TestComponent(DefaultCursor.create(region,Sets.empty())).fork());
		regions.mergeAt(Sets.of(marker),key);
		assertNull(host.cursor().get().get(key));
		regions.mergeAt(Sets.of(marker),unrelated);
		regions.sync();
		assertEquals(Sets.of(marker),host.cursor().get().get(key));
		assertSame(marker,host.cursor().get().get(unrelated));
	}
}
