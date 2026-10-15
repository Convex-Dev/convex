package convex.lattice.app;

import static org.junit.jupiter.api.Assertions.*;
import static convex.lattice.app.FavouritesExample.*;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import convex.core.data.Sets;
import convex.core.data.prim.CVMLong;
import convex.etch.EtchStore;
import convex.lattice.LatticeHost;

/** Store-backed variant of the in-memory favourites application. */
class FavouritesPersistenceTest {
	@Test void aliceAndBobPublishTheirEditTimesAndReopen(@TempDir Path directory) throws Exception {
		var file=directory.resolve("favourites.etch").toFile();
		try (var store=EtchStore.create(file)) {
			var localHost=LatticeHost.open(store);
			var local=new FavouritesApp(localHost);
			localHost.configureApplications(List.of(local));
			var alice=local.user(ALICE);
			var bob=local.user(BOB);
			var favourites=alice.favourites();
			local.setTime(100);
			favourites.add(10);
			bob.favourites().add(30);
			local.setTime(200);
			alice.favourites().add(20); // Another view shares the same user state and clock.
			assertEquals(Sets.of(10,20),favourites.items(),"Views share the application working state");
			assertEquals(Sets.of(30),bob.favourites().items());
			assertTrue(local.record(ALICE.getAccountKey()).checkSignature());
			assertTrue(local.record(BOB.getAccountKey()).checkSignature());

			local.setTime(1000); // Application publication must not replace the edit timestamps.
			assertEquals(LATTICE.zero(),localHost.cursor().get(),"Signing publishes into application working state only");
			local.sync();
			assertEquals(local.cursor().get(),localHost.cursor().get());
			assertEquals(CVMLong.create(200),local.record(ALICE.getAccountKey()).getValue().get(TIMESTAMP));
			assertEquals(CVMLong.create(100),local.record(BOB.getAccountKey()).getValue().get(TIMESTAMP));
			assertNull(store.getRootData(),"Application publication stops at the host");
			localHost.sync();
		}
		try (var store=EtchStore.create(file)) {
			var reopenedHost=LatticeHost.open(store);
			var reopened=new FavouritesApp(reopenedHost);
			reopenedHost.configureApplications(List.of(reopened));
			assertEquals(Sets.of(10,20),reopened.items(ALICE.getAccountKey()));
			assertEquals(Sets.of(30),reopened.items(BOB.getAccountKey()));
			assertEquals(CVMLong.create(200),reopened.record(ALICE.getAccountKey()).getValue().get(TIMESTAMP));
			assertEquals(CVMLong.create(100),reopened.record(BOB.getAccountKey()).getValue().get(TIMESTAMP));
			assertTrue(reopened.record(ALICE.getAccountKey()).checkSignature());
			assertTrue(reopened.record(BOB.getAccountKey()).checkSignature());
		}
	}

}
