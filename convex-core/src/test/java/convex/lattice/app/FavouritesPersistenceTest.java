package convex.lattice.app;

import static org.junit.jupiter.api.Assertions.*;
import static convex.lattice.app.FavouritesExample.*;

import java.io.File;
import java.util.List;

import org.junit.jupiter.api.Test;

import convex.core.data.ACell;
import convex.core.data.Index;
import convex.core.data.Keyword;
import convex.core.data.Sets;
import convex.core.data.prim.CVMLong;
import convex.etch.EtchStore;
import convex.lattice.LatticeHost;

/**
 * The same {@link FavouritesExample} components over an Etch-backed host.
 * This test separates three steps: edits sign into region working state,
 * application sync publishes that region to the host, and host sync records the
 * retained root in the store. Reopening restores the signed values without a
 * domain edit, a new timestamp or access to an owner's private key for reading.
 */
class FavouritesPersistenceTest {
	@Test void aliceAndBobPublishTheirEditTimesAndReopen() throws Exception {
		File file;
		// MappedByteBuffer may keep Windows mappings alive after close; use Etch's
		// temporary-file lifecycle rather than requiring immediate JUnit deletion.
		try (var store=EtchStore.createTemp("favourites")) {
			file=store.getFile();
			var localHost=LatticeHost.open(store);
			var local=new FavouritesApp(localHost);
			localHost.configureApplications(List.of(local));
			var alice=local.favourites().user(ALICE);
			var bob=local.favourites().user(BOB);
			var favourites=local.favourites().user(ALICE);
			// Two Alice handles share one owner record; Bob's record has its own edit time.
			local.favourites().setTime(100);
			favourites.add(10);
			bob.add(30);
			local.favourites().setTime(200);
			alice.add(20); // Both handles share the same user state and clock.
			assertEquals(Sets.of(10,20),favourites.items(),"Views share the region working state");
			assertEquals(Sets.of(30),bob.items());
			assertTrue(local.favourites().record(ALICE.getAccountKey()).checkSignature());
			assertTrue(local.favourites().record(BOB.getAccountKey()).checkSignature());

			local.favourites().setTime(1000); // Application publication must not replace the edit timestamps.
			assertEquals(Index.<Keyword,ACell>none(),localHost.cursor().get(),"Signing publishes into region working state only");
			local.sync();
			assertEquals(local.favourites().cursor().get(),localHost.cursor().get().get(FAVOURITES));
			assertEquals(CVMLong.create(200),local.favourites().record(ALICE.getAccountKey()).getValue().get(TIMESTAMP));
			assertEquals(CVMLong.create(100),local.favourites().record(BOB.getAccountKey()).getValue().get(TIMESTAMP));
			assertNull(store.getRootData(),"Application publication stops at the host");
			// Only this step records the host root for a future open.
			localHost.sync();
		}
		// A fresh store and host prove restoration from the retained root, not a live cursor.
		try (var store=EtchStore.create(file)) {
			var reopenedHost=LatticeHost.open(store);
			var reopened=new FavouritesApp(reopenedHost);
			reopenedHost.configureApplications(List.of(reopened));
			assertEquals(Sets.of(10,20),reopened.favourites().items(ALICE.getAccountKey()));
			assertEquals(Sets.of(30),reopened.favourites().items(BOB.getAccountKey()));
			assertEquals(CVMLong.create(200),reopened.favourites().record(ALICE.getAccountKey()).getValue().get(TIMESTAMP));
			assertEquals(CVMLong.create(100),reopened.favourites().record(BOB.getAccountKey()).getValue().get(TIMESTAMP));
			assertTrue(reopened.favourites().record(ALICE.getAccountKey()).checkSignature());
			assertTrue(reopened.favourites().record(BOB.getAccountKey()).checkSignature());
		}
	}

}
