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
import convex.lattice.LatticeHost;
import convex.lattice.SetComponent;
import convex.lattice.SingleRegion;
import convex.lattice.cursor.AForkableCursor;
import convex.lattice.generic.SetLattice;
import convex.node.LatticeNode;
import convex.node.LatticePropagator;
import convex.node.NodeConfig;

/** Two reading-list applications exchange published recommendations over a real connection. */
class ReadingListApplicationTest {
	private static final Keyword BOOKS=Keyword.intern("books");
	private static final AKeyPair ALICE_NODE=AKeyPair.createSeeded(9190);
	private static final AKeyPair BOB_NODE=AKeyPair.createSeeded(9191);

	/** Routes the books region to its component, which owns merging and publication. */
	static final class ReadingListApp extends SingleRegion<Books> {
		ReadingListApp(LatticeHost<Index<Keyword,ACell>> host) {
			super(host,BOOKS,new Books(host.cursor().path(BOOKS)).fork());
		}
		void recommend(String title) { component().recommend(title); }
		ASet<AString> titles() { return component().titles(); }
	}

	/** Staged recommendations, combined by union both on receipt and on publication. */
	static final class Books extends SetComponent<AString> {
		Books(AForkableCursor<ASet<AString>> cursor) { super(cursor); }
		@Override protected Books rebind(AForkableCursor<ASet<AString>> cursor) {
			return new Books(cursor);
		}
		@Override public Books fork() { return rebind(storage.fork()); }
		void recommend(String title) { add(Strings.create(title)); }
		ASet<AString> titles() { return get(); }

		@Override public ASet<AString> merge(ASet<AString> incoming) {
			return cursor().updateAndGet(own -> mergePublication(own,incoming));
		}
		@Override protected ASet<AString> mergePublication(ASet<AString> own,ASet<AString> other) {
			return SetLattice.<AString>create().merge(own,other);
		}
	}

	/** Independent drafts reconcile at their shared region without rewriting sibling regions. */
	@Test void publicationUnionsCompetingDraftsAndPreservesOtherRegions() {
		var host=LatticeHost.create();
		var other=Keyword.intern("other");
		host.cursor().assoc(other,Strings.create("untouched"));
		var initial=host.cursor().get();
		var alice=host.addApplication(new ReadingListApp(host));
		var bob=host.addApplication(new ReadingListApp(host));
		assertTrue(alice.titles().isEmpty());
		assertEquals(initial,host.cursor().get(),"reading an absent set must not create a region");

		alice.recommend("The Dispossessed");
		bob.recommend("A Wizard of Earthsea");
		host.mergeAt(Sets.of("The Left Hand of Darkness"),BOOKS);
		assertEquals(Sets.of("The Dispossessed","The Left Hand of Darkness"),alice.titles());
		assertEquals(Sets.of("A Wizard of Earthsea","The Left Hand of Darkness"),bob.titles());
		assertEquals(initial,host.cursor().get(),"incoming values stay in the drafts");

		alice.sync();
		bob.sync(); // The destination has changed since Bob forked it.
		var expected=initial.assoc(BOOKS,Sets.of(
			"The Dispossessed","A Wizard of Earthsea","The Left Hand of Darkness"));
		assertEquals(expected,host.cursor().get());
		assertEquals(expected.get(BOOKS),bob.titles(),"publication also reconciles the working copy");
		bob.sync();
		assertEquals(expected,host.cursor().get(),"repeated publication is idempotent");
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
				var expectedRoot=Index.of(BOOKS,expectedTitles);
				assertEquals(expectedTitles,bob.titles());
				assertEquals(Index.none(),bobHost.cursor().get());
				assertEquals(Index.none(),bobStore.getRootData());

				// Application sync publishes into the host, without advancing the stored root.
				bob.sync();
				assertEquals(expectedRoot,bobHost.cursor().get());
				assertEquals(Index.none(),bobStore.getRootData());

				// Host sync advances the stored root and schedules propagation.
				bobHost.sync();
				assertEquals(expectedRoot,bobStore.getRootData());
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
