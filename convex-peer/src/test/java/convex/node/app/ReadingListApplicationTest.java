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
import convex.etch.EtchStore;
import convex.lattice.LatticeApplication;
import convex.lattice.ALatticeComponent;
import convex.lattice.LatticeHost;
import convex.lattice.cursor.AForkableCursor;
import convex.lattice.generic.KeyedLattice;
import convex.lattice.generic.SetLattice;
import convex.node.LatticeNode;
import convex.node.NodeConfig;

/** Two reading-list applications exchange published recommendations over a real connection. */
class ReadingListApplicationTest {
	private static final Keyword BOOKS=Keyword.intern("books");
	private static final KeyedLattice LATTICE=KeyedLattice.create(BOOKS,SetLattice.create());
	private static final AKeyPair ALICE_NODE=AKeyPair.createSeeded(9190);
	private static final AKeyPair BOB_NODE=AKeyPair.createSeeded(9191);

	static final class ReadingListApp extends LatticeApplication<Index<Keyword,ACell>> {
		ReadingListApp(LatticeHost<Index<Keyword,ACell>> host) { super(LATTICE,host); }
		@Override public Index<Keyword,ACell> merge(Index<Keyword,ACell> other) {
			return cursor().updateAndGet(own -> getLattice().merge(getContext(),own,other));
		}
		Books books() { return new Books(resolve(BOOKS)); }
	}

	static final class Books extends ALatticeComponent<ASet<AString>> {
		Books(AForkableCursor<ASet<AString>> cursor) { super(cursor); }
		void recommend(String title) {
			cursor().updateAndGet(books -> (books==null?Sets.<AString>empty():books).include(Strings.create(title)));
		}
		ASet<AString> titles() { return cursor().get()==null?Sets.empty():cursor().get(); }
	}

	@Test void receivedRecommendationsStayPrivateUntilTheApplicationPublishes() throws Exception {
		try (var aliceStore=EtchStore.createTemp("reading-list-alice");
				var bobStore=EtchStore.createTemp("reading-list-bob")) {
			var aliceHost=LatticeHost.<Index<Keyword,ACell>>open(aliceStore);
			var bobHost=LatticeHost.<Index<Keyword,ACell>>open(bobStore);
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

				alice.books().recommend("The Dispossessed");
				alice.sync();
				aliceHost.sync();
				long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
				while (true) {
					var next=aliceGroup.nextAnnounce();
					if (aliceHost.cursor().get().equals(aliceGroup.getLastAnnouncedValue())) break;
					next.get(Math.max(1,deadline-System.nanoTime()),TimeUnit.NANOSECONDS);
				}

				bob.books().recommend("A Wizard of Earthsea");
				try (var peer=ConvexRemote.connect(aliceNetwork.getHostAddress())) {
					bobGroup.addPeer(aliceGroup.getTransportKey(),peer).get(10,TimeUnit.SECONDS);
					bobNetwork.getServer().pull(bobGroup,peer).get(10,TimeUnit.SECONDS);
				}
				assertEquals(Sets.of("The Dispossessed","A Wizard of Earthsea"),bob.books().titles());
				assertTrue(bobHost.cursor().get().isEmpty());
				assertTrue(bobStore.<Index<Keyword,ACell>>getRootData().isEmpty());
				bob.sync();
				bobHost.sync();
				bobHost.flush();
				assertEquals(bob.cursor().get(),bobStore.getRootData());
			}
		}
	}
}
