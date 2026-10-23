package convex.node.app;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import convex.api.ConvexRemote;
import convex.auth.did.DID;
import convex.core.crypto.AKeyPair;
import convex.core.data.ACell;
import convex.core.data.ASet;
import convex.core.data.AString;
import convex.core.data.Index;
import convex.core.data.Keyword;
import convex.core.data.Sets;
import convex.core.data.Strings;
import convex.core.store.MemoryStore;
import convex.etch.EtchStore;
import convex.lattice.LatticeApplication;
import convex.lattice.LatticeHost;
import convex.lattice.SetComponent;
import convex.lattice.cursor.AForkableCursor;
import convex.lattice.generic.KeyedLattice;
import convex.lattice.generic.SetLattice;
import convex.node.LatticeNode;
import convex.node.LatticePropagator;
import convex.node.NodeConfig;

/** Two reading-list applications exchange published recommendations over a real connection. */
class ReadingListApplicationTest {
	private static final Keyword BOOKS=Keyword.intern("books");
	private static final KeyedLattice LATTICE=KeyedLattice.create(BOOKS,SetLattice.create());
	private static final AKeyPair ALICE_NODE=AKeyPair.createSeeded(9190);
	private static final AKeyPair BOB_NODE=AKeyPair.createSeeded(9191);

	/** Stages recommendations privately and merges incoming lists by set union. */
	static final class ReadingListApp extends LatticeApplication<Index<Keyword,ACell>> {
		private final Books books;

		ReadingListApp(LatticeHost<Index<Keyword,ACell>> host) {
			super(LATTICE,host);
			books=new Books(resolve(BOOKS));
		}
		void recommend(String title) { books.recommend(title); }
		ASet<AString> titles() { return books.titles(); }
	}

	/** Domain names for ordinary set operations; the application supplies the merge policy. */
	static final class Books extends SetComponent<AString> {
		Books(AForkableCursor<ASet<AString>> cursor) { super(cursor); }
		void recommend(String title) { add(Strings.create(title)); }
		ASet<AString> titles() { return get(); }
	}

	@Test void receivedRecommendationsStayPrivateUntilTheApplicationPublishes() throws Exception {
		try (var aliceStore=new MemoryStore();
				var bobStore=EtchStore.createTemp("reading-list-bob")) {
			var aliceHost=LatticeHost.open(aliceStore);
			var bobHost=LatticeHost.open(bobStore);
			var alice=aliceHost.addApplication(new ReadingListApp(aliceHost));
			var bob=bobHost.addApplication(new ReadingListApp(bobHost));
			// Networking is optional and scoped independently of the applications.
			try (var aliceNetwork=new LatticeNode<>(aliceHost,NodeConfig.localNetwork()).launch();
					var bobNetwork=new LatticeNode<>(bobHost,NodeConfig.localNetwork()).launch()) {
				aliceNetwork.setIdentity(DID.forKey(ALICE_NODE.getAccountKey()),ALICE_NODE);
				bobNetwork.setIdentity(DID.forKey(BOB_NODE.getAccountKey()),BOB_NODE);
				// Groups can be added after launch and inherit the node's configured key.
				var aliceGroup=aliceNetwork.createPropagator();
				var bobGroup=bobNetwork.createPropagator();
				aliceNetwork.serveAllInbound(aliceGroup);

				// Alice publishes her recommendation and waits until it can be served.
				alice.recommend("The Dispossessed");
				alice.sync();
				awaitAnnounced(aliceGroup,aliceHost.sync());

				// Receiving Alice's list preserves Bob's pending recommendation.
				bob.recommend("A Wizard of Earthsea");
				try (var peer=ConvexRemote.connect(aliceNetwork.getHostAddress())) {
					bobGroup.addPeer(aliceGroup.getTransportKey(),peer).get(10,TimeUnit.SECONDS);
					bobNetwork.getServer().pull(bobGroup,peer).get(10,TimeUnit.SECONDS);
				}
				var expectedTitles=Sets.of("The Dispossessed","A Wizard of Earthsea");
				assertEquals(expectedTitles,bob.titles());
				assertTrue(bobHost.cursor().get().isEmpty());
				assertTrue(bobStore.<Index<Keyword,ACell>>getRootData().isEmpty());

				// Application sync publishes into the host, without advancing the stored root.
				bob.sync();
				assertEquals(expectedTitles,bobHost.cursor().get().get(BOOKS));
				assertTrue(bobStore.<Index<Keyword,ACell>>getRootData().isEmpty());

				// Host sync advances the stored root and schedules propagation.
				bobHost.sync();
				assertEquals(expectedTitles,bobStore.<Index<Keyword,ACell>>getRootData().get(BOOKS));
			}
		}
	}

	/** Waits for this exact snapshot; this scenario makes no further edits while waiting. */
	private static void awaitAnnounced(LatticePropagator group,ACell expected) throws Exception {
		long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
		while (true) {
			// Subscribe before inspecting the view so a concurrent announcement cannot be missed.
			var next=group.nextAnnounce();
			if (expected.equals(group.getLastAnnouncedValue())) return;
			next.get(Math.max(1,deadline-System.nanoTime()),TimeUnit.NANOSECONDS);
		}
	}
}
