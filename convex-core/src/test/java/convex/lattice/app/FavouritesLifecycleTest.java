package convex.lattice.app;

import static convex.lattice.app.FavouritesExample.*;
import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import convex.core.data.Index;
import convex.core.data.Sets;
import convex.core.data.prim.CVMLong;
import convex.core.store.MemoryStore;
import convex.etch.EtchStore;
import convex.lattice.LatticeHost;

/** Executable implementer recipes: save, reopen, batch, isolate drafts and replace. */
class FavouritesLifecycleTest {
	@Test void saveOneApplicationWithoutAcceptingAnotherApplicationsDraft() throws Exception {
		try (var store=EtchStore.createTemp("favourites-lifecycle")) {
			var host=LatticeHost.open(store);
			var aliceApp=host.addApplication(new FavouritesApp(host,() -> CVMLong.ONE));
			var bobApp=host.addApplication(new FavouritesApp(host,() -> CVMLong.ONE));
			aliceApp.user(ALICE).add(42);
			bobApp.user(BOB).add(99);

			aliceApp.sync();
			host.checkpoint();

			var reopened=LatticeHost.open(store);
			var reader=reopened.addApplication(new FavouritesApp(reopened,() -> CVMLong.ZERO));
			assertEquals(Sets.of(42),reader.items(ALICE.getAccountKey()));
			assertNull(reader.items(BOB.getAccountKey()));
			assertEquals(Sets.of(99),bobApp.items(BOB.getAccountKey()));
		}
	}

	@Test void localServiceFlushesOnlyPublishedStateAndLeavesItsStoreUsable() throws Exception {
		var flushed=new CountDownLatch(1);
		try (var store=new MemoryStore() {
			@Override public void flush() { flushed.countDown(); }
		}) {
			var host=LatticeHost.open(store);
			var app=host.addApplication(new FavouritesApp(host,() -> CVMLong.ONE));
			app.user(ALICE).add(42);
			app.sync();
			host.sync();
			app.user(BOB).add(99); // Still private when the service closes.
			try (var durability=host.autoFlush(Duration.ofMillis(1))) {
				assertTrue(flushed.await(10,TimeUnit.SECONDS));
			}
			var restored=LatticeHost.open(store);
			var reader=restored.addApplication(new FavouritesApp(restored,() -> CVMLong.ZERO));
			assertEquals(Sets.of(42),reader.items(ALICE.getAccountKey()));
			assertNull(reader.items(BOB.getAccountKey()));
			host.checkpoint(); // The resource did not close the borrowed store.
		}
	}

	@Test void privateHostDraftCanBeSavedReopenedAndExplicitlyAccepted() throws Exception {
		var origin=LatticeHost.create();
		var original=origin.addApplication(new FavouritesApp(origin,() -> CVMLong.ONE));
		File file;
		try (var store=EtchStore.createTemp("favourites-private-draft")) {
			file=store.getFile();
			var draftHost=origin.fork(store);
			var draft=draftHost.addApplication(new FavouritesApp(draftHost,() -> CVMLong.ONE));
			draft.user(ALICE).add(42);
			draft.sync();
			draftHost.checkpoint(); // Saves privately; unlike fork.sync(), does not exchange roots.
			assertNull(original.items(ALICE.getAccountKey()));
			assertTrue(origin.cursor().get().isEmpty());
		}
		try (var store=EtchStore.create(file)) {
			var reopened=LatticeHost.open(store);
			var reader=reopened.addApplication(new FavouritesApp(reopened,() -> CVMLong.ZERO));
			assertEquals(Sets.of(42),reader.items(ALICE.getAccountKey()));
			// A reopened host has no hidden origin. Accept through ordinary delivery.
			origin.merge(reopened.cursor().get());
			assertTrue(origin.cursor().get().isEmpty());
			original.sync();
			origin.checkpoint();
			assertEquals(Sets.of(42),original.items(ALICE.getAccountKey()));
		}
	}

	@Test void openingPreservesStateAndReplacementIsExplicit() throws Exception {
		try (var store=EtchStore.createTemp("favourites-opening")) {
			var host=LatticeHost.create(store);
			var app=host.addApplication(new FavouritesApp(host,() -> CVMLong.ONE));
			app.user(ALICE).add(42);
			app.sync();
			var saved=host.checkpoint();
			assertThrows(IllegalStateException.class,() -> LatticeHost.create(store));
			assertEquals(saved,LatticeHost.open(store).cursor().get());
			var replacement=LatticeHost.replace(Index.none(),store);
			replacement.checkpoint();
			assertTrue(LatticeHost.open(store).cursor().get().isEmpty());
		}
	}
}
