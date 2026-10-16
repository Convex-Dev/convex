package convex.node.app;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import convex.api.ConvexRemote;
import convex.core.crypto.AKeyPair;
import convex.core.data.ACell;
import convex.core.data.ASet;
import convex.core.data.AString;
import convex.core.data.Index;
import convex.core.data.Keyword;
import convex.core.data.Sets;
import convex.core.data.Strings;
import convex.etch.EtchStore;
import convex.lattice.ALatticeApplication;
import convex.lattice.ALatticeComponent;
import convex.lattice.LatticeHost;
import convex.lattice.cursor.AForkableCursor;
import convex.lattice.generic.KeyedLattice;
import convex.lattice.generic.SetLattice;
import convex.node.LatticeListener;
import convex.node.LatticePropagator;
import convex.node.NodeConfig;
import convex.node.NodeServer;

/** Two reading-list applications exchange published recommendations over a real connection. */
class ReadingListApplicationTest {
	private static final Keyword BOOKS=Keyword.intern("books");
	private static final KeyedLattice LATTICE=KeyedLattice.create(BOOKS,SetLattice.create());
	private static final AKeyPair ALICE_NODE=AKeyPair.createSeeded(9190);
	private static final AKeyPair BOB_NODE=AKeyPair.createSeeded(9191);

	static final class ReadingListApp extends ALatticeApplication<Index<Keyword,ACell>> {
		ReadingListApp(LatticeHost<Index<Keyword,ACell>> host) { super(LATTICE,host); }
		Books books() { return new Books(resolve(BOOKS)); }
	}

	static final class Books extends ALatticeComponent<ASet<AString>> {
		Books(AForkableCursor<ASet<AString>> cursor) { super(cursor); }
		void recommend(String title) {
			cursor.updateAndGet(books -> (books==null?Sets.<AString>empty():books).include(Strings.create(title)));
		}
		ASet<AString> titles() { return cursor.get()==null?Sets.empty():cursor.get(); }
	}

	/** The caller owns networking and stores; the application itself has no workers. */
	static final class RunningList implements AutoCloseable {
		final LatticeHost<Index<Keyword,ACell>> host;
		final ReadingListApp app;
		final NodeServer<Index<Keyword,ACell>> node;
		final LatticePropagator group;
		final LatticeListener listener;

		RunningList(EtchStore store,AKeyPair transportKey) throws Exception {
			host=LatticeHost.open(store);
			app=new ReadingListApp(host);
			host.configureApplications(List.of(app));
			var config=NodeConfig.localNetwork();
			node=new NodeServer<>(host,config);
			group=new LatticePropagator(store);
			group.setTransportKeyPair(transportKey);
			node.addPropagator(group);
			listener=new LatticeListener(config);
			listener.registerPropagator(group);
			listener.setSelector(connection -> group);
			node.launch();
			listener.launch();
		}

		@Override public void close() throws Exception {
			try { listener.close(); }
			finally { node.close(); }
		}
	}

	@Test void receivedRecommendationsStayPrivateUntilTheApplicationPublishes() throws Exception {
		try (var aliceStore=EtchStore.createTemp("reading-list-alice");
				var bobStore=EtchStore.createTemp("reading-list-bob");
				var alice=new RunningList(aliceStore,ALICE_NODE);
				var bob=new RunningList(bobStore,BOB_NODE)) {
			alice.app.books().recommend("The Dispossessed");
			alice.app.sync();
			alice.host.sync();
			long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
			while (true) {
				var next=alice.group.nextAnnounce();
				if (alice.host.cursor().get().equals(alice.group.getLastAnnouncedValue())) break;
				next.get(Math.max(1,deadline-System.nanoTime()),TimeUnit.NANOSECONDS);
			}

			bob.app.books().recommend("A Wizard of Earthsea");
			try (var peer=ConvexRemote.connect(alice.listener.getHostAddress())) {
				bob.group.addPeer(ALICE_NODE.getAccountKey(),peer).get(10,TimeUnit.SECONDS);
				bob.node.pull(bob.group,peer).get(10,TimeUnit.SECONDS);
			}
			assertEquals(Sets.of("The Dispossessed","A Wizard of Earthsea"),bob.app.books().titles());
			assertTrue(bob.host.cursor().get().isEmpty());
			assertTrue(bobStore.<Index<Keyword,ACell>>getRootData().isEmpty());
			bob.app.sync();
			bob.host.sync();
			bob.host.flush();
			assertEquals(bob.app.cursor().get(),bobStore.getRootData());
		}
	}
}
