package convex.lattice;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import convex.core.data.ACell;
import convex.core.data.ASet;
import convex.core.data.Blob;
import convex.core.data.Blobs;
import convex.core.data.Index;
import convex.core.data.Keyword;
import convex.core.data.Sets;
import convex.core.data.prim.CVMLong;
import convex.core.store.MemoryStore;
import convex.lattice.cursor.TransactionalRootCursor;
import convex.lattice.generic.KeyedLattice;
import convex.lattice.generic.SetLattice;

/** A host fork is a detached root with its own applications, syncing by exchange with its origin. */
class LatticeHostForkTest {
	private static final Keyword ITEMS=Keyword.intern("items");
	private static final KeyedLattice LATTICE=KeyedLattice.create(ITEMS,SetLattice.create());

	/** A live application over the host, registered to receive deliveries. */
	private static LatticeApplication<Index<Keyword,ACell>> live(LatticeHost<Index<Keyword,ACell>> host) {
		return host.addApplication(LatticeApplication.live(LATTICE,host));
	}

	private static void add(LatticeApplication<Index<Keyword,ACell>> app,long item) {
		app.<ASet<ACell>>resolve(ITEMS).updateAndGet(items ->
			(items==null?Sets.<ACell>empty():items).include(CVMLong.create(item)));
	}

	@SuppressWarnings("unchecked")
	private static ASet<ACell> items(Index<Keyword,ACell> root) {
		return (ASet<ACell>)root.get(ITEMS);
	}

	private static ASet<ACell> items(LatticeHost<Index<Keyword,ACell>> host) {
		return items(host.cursor().get());
	}

	@Test void forkIsADetachedCopyWithItsOwnApplicationsAndNoStore() throws Exception {
		try (var store=new MemoryStore()) {
			var origin=LatticeHost.create(store);
			var app=live(origin);
			add(app,1);
			var fork=origin.fork();
			assertEquals(origin.cursor().get(),fork.cursor().get(),"the fork starts from the origin's root");
			assertSame(origin,fork.origin());
			assertNull(origin.origin());
			assertNull(fork.store(),"a fork has no store unless given one");
			add(app,2);
			assertEquals(Sets.of(1),items(fork),"later origin edits do not reach a detached fork");
			fork.merge(origin.cursor().get());
			assertEquals(Sets.of(1),items(fork),"a fork with no applications ignores incoming values");
		}
	}

	@Test void syncExchangesRootsWithTheOrigin() {
		var origin=LatticeHost.create();
		var originApp=live(origin);
		var fork=origin.fork();
		var forkApp=live(fork);
		add(forkApp,1);
		add(originApp,2);
		assertEquals(Sets.of(2),items(origin));
		assertEquals(Sets.of(1),items(fork));
		var result=fork.sync();
		assertEquals(Sets.of(1,2),items(origin),"the fork's root was delivered to the origin's applications");
		assertEquals(Sets.of(1,2),items(fork),"the origin's root came back to the fork's applications");
		assertEquals(fork.cursor().get(),result);
	}

	@Test void forkNeverWritesTheOriginStore() throws Exception {
		try (var store=new MemoryStore(); var forkStore=new MemoryStore()) {
			var origin=LatticeHost.create(store);
			var originApp=live(origin);
			origin.sync();
			var retained=store.getRootData();

			var persistent=origin.fork(forkStore);
			add(live(persistent),2);
			add(originApp,1);
			persistent.sync();
			assertEquals(Sets.of(2),items(forkStore.getRootData()),"a fork's store holds what the fork published, before the exchange");
			assertEquals(Sets.of(1,2),items(origin),"the fork's root was delivered to the origin");
			assertEquals(Sets.of(1,2),items(persistent),"the exchange brought the origin's root back");
			assertEquals(retained,store.getRootData(),"the origin's store is untouched");

			var inMemory=origin.fork();
			add(live(inMemory),3);
			inMemory.sync();
			assertEquals(retained,store.getRootData(),"an in-memory fork persists nowhere");
			assertEquals(Sets.of(1,2,3),items(origin));

			origin.sync();
			assertEquals(origin.cursor().get(),store.getRootData(),"only the origin's own sync writes its store");
		}
	}

	@Test void forkRefusesItsOriginsStore() throws Exception {
		try (var store=new MemoryStore()) {
			var origin=LatticeHost.create(store);
			assertThrows(IllegalArgumentException.class,() -> origin.fork(store));
			assertNotNull(origin.fork(),"an in-memory fork is always available");
		}
	}

	/** Each side's store receives the other's cells at the exchange; root pointers move only on each host's own sync. */
	@Test void exchangeCarriesCellsIntoEachSidesStore() throws Exception {
		try (var store=new MemoryStore(); var scratch=new MemoryStore()) {
			var origin=LatticeHost.create(store);
			var originApp=live(origin);
			var fork=origin.fork(scratch);
			var forkApp=live(fork);
			Blob forkBlob=Blobs.createRandom(400);
			Blob originBlob=Blobs.createRandom(400);
			forkApp.<ASet<ACell>>resolve(ITEMS).updateAndGet(items -> Sets.of(forkBlob));
			originApp.<ASet<ACell>>resolve(ITEMS).updateAndGet(items -> Sets.of(originBlob));
			assertNull(store.refForHash(forkBlob.getHash()));
			assertNull(scratch.refForHash(originBlob.getHash()));

			fork.sync();
			assertNotNull(store.refForHash(forkBlob.getHash()),"the fork's cells reached the origin's store");
			assertNotNull(scratch.refForHash(originBlob.getHash()),"the origin's cells reached the fork's store");
			assertNull(store.getRootData(),"the origin's root pointer moves only on its own sync");
			assertEquals(Sets.of(forkBlob,originBlob),items(origin));
			assertEquals(Sets.of(forkBlob,originBlob),items(fork));
		}
	}

	@Test void nestedForkDeliversToItsParentOnly() {
		var origin=LatticeHost.create();
		live(origin);
		var fork=origin.fork();
		live(fork);
		var nested=fork.fork();
		add(live(nested),1);
		nested.sync();
		assertEquals(Sets.of(1),items(fork));
		assertNull(items(origin),"the origin sees nothing until the fork itself syncs");
		fork.sync();
		assertEquals(Sets.of(1),items(origin));
	}

	@Test void forkRootMirrorsTheOriginsTransactionalKind() {
		var plain=LatticeHost.create();
		assertFalse(plain.cursor().supportsExclusiveTransactions());
		assertFalse(plain.fork().cursor().supportsExclusiveTransactions());
		var transactional=new LatticeHost<>(new TransactionalRootCursor<>(LATTICE.zero()),null);
		assertTrue(transactional.cursor().supportsExclusiveTransactions());
		assertTrue(transactional.fork().cursor().supportsExclusiveTransactions());
	}
}
