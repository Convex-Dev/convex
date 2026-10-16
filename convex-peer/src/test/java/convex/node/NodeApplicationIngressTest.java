package convex.node;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import convex.api.Convex;
import convex.api.ConvexRemote;
import convex.core.crypto.AKeyPair;
import convex.core.data.ACell;
import convex.core.data.Index;
import convex.core.data.Keyword;
import convex.core.data.Sets;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;
import convex.core.message.Message;
import convex.core.message.MessageTag;
import convex.core.message.MessageType;
import convex.core.store.MemoryStore;
import convex.etch.EtchStore;
import convex.lattice.ALatticeApplication;
import convex.lattice.LatticeContext;
import convex.lattice.LatticeHost;
import convex.lattice.generic.KeyedLattice;
import convex.lattice.generic.SetLattice;

class NodeApplicationIngressTest {
	private static final Keyword ITEMS=Keyword.intern("items");
	private static final KeyedLattice LATTICE=KeyedLattice.create(ITEMS,SetLattice.create());
	private static class App extends ALatticeApplication<Index<Keyword,ACell>> {
		int preparations;
		int merges;
		boolean ignore;
		App(LatticeHost<Index<Keyword,ACell>> host) { super(LATTICE,host); }
		@Override public Index<Keyword,ACell> merge(Index<Keyword,ACell> incoming) {
			merges++;
			return ignore?cursor.get():super.merge(incoming);
		}
		@Override protected void beforeSync() { preparations++; }
		@Override public Index<Keyword,ACell> mergeAt(ACell value,ACell... path) {
			if (path.length==1 && ITEMS.equals(path[0])) return merge(LATTICE.zero().assoc(ITEMS,value));
			return super.mergeAt(value,path);
		}
	}

	@Test void applicationSeesRestoredStateBeforePropagationStarts() throws Exception {
		try (var store=EtchStore.createTemp("application-launch")) {
			var retained=LATTICE.zero().assoc(ITEMS,Sets.of(CVMLong.ONE));
			store.setRootData(retained);
			var host=LatticeHost.open(store);
			var app=new App(host);
			host.configureApplications(List.of(app));
			try (var node=new NodeServer<>(host)) {
				assertEquals(retained,app.cursor().get());
				assertFalse(node.isRunning());
				node.launch();
				node.mergeAt(Sets.of(CVMLong.TWO),ITEMS);
				assertEquals(Sets.of(CVMLong.ONE,CVMLong.TWO),app.cursor().get().get(ITEMS));
				assertEquals(retained,node.getLocalValue());
				assertEquals(0,app.preparations);
				assertThrows(IllegalStateException.class,node::launch);
			}
		}
	}

	@Test void restoreFailurePreventsHostAssembly() throws Exception {
		try (var store=new MemoryStore() {
			@Override public <T extends ACell> T getRootData() throws IOException {
				throw new IOException("unreadable retained root");
			}
		}) {
			var failure=assertThrows(IOException.class,() -> LatticeHost.open(store));
			assertEquals("unreadable retained root",failure.getMessage());
			assertNull(store.getRootHash());
		}
	}

	@Test void relayRestoreFailureDoesNotReplaceRoot() throws Exception {
		try (var store=new MemoryStore() {
			@Override public boolean isPersistent() { return true; }
			@Override public <T extends ACell> T getRootData() throws IOException {
				throw new IOException("unreadable retained root");
			}
		}; var node=new NodeServer<>(LATTICE,store)) {
			assertThrows(IOException.class,node::launch);
			assertFalse(node.isRunning());
			assertNull(store.getRootHash());
		}
	}

	@Test void suppliedRootKeepsInitialisationContextAndCursorPolicy() throws Exception {
		try (var store=EtchStore.createTemp("supplied-root")) {
			var retained=LATTICE.zero().assoc(ITEMS,Sets.of(CVMLong.ONE));
			store.setRootData(retained);
			var root=LatticeHost.open(LATTICE.zero(),store);
			var context=LatticeContext.EMPTY.withMaxFutureTimestampSkew(1234L);
			root.setContext(context);
			// A supplied root is already initialised: launch must not reload the store.
			store.setRootData(LATTICE.zero().assoc(ITEMS,Sets.of(CVMLong.TWO)));
			try (var node=new NodeServer<>(root,NodeConfig.create().withTransactionalRoot(true))) {
				assertSame(root,node.getHost());
				assertSame(root.cursor(),node.getCursor());
				assertSame(store,node.getStore());
				assertSame(context,node.getMergeContext());
				assertFalse(node.getCursor().supportsExclusiveTransactions());
				var app=new App(node.getHost());
			node.getHost().configureApplications(List.of(app));
			node.launch();
				assertSame(context,app.getContext());
				assertEquals(retained,app.cursor().get());
				assertEquals(retained,store.getRootData());
			}
		}
	}

	@Test void suppliedRootDelegatesToAlreadyConfiguredApplications() throws Exception {
		try (var store=new MemoryStore()) {
			var root=LatticeHost.create(KeyedLattice.create().zero(),store);
			var app=new App(root);
			root.configureApplications(List.of(app));
			try (var node=new NodeServer<>(root)) {
				node.launch();
				var incoming=LATTICE.zero().assoc(ITEMS,Sets.of(CVMLong.ONE));
				node.mergeValue(incoming);
				node.mergeAt(Sets.of(CVMLong.TWO),ITEMS);
				assertEquals(2,app.merges);
				assertEquals(Sets.of(CVMLong.ONE,CVMLong.TWO),app.cursor().get().get(ITEMS));
				assertEquals(LATTICE.zero(),node.getLocalValue());
				assertEquals(0,app.preparations);
				app.sync();
				root.sync();
				assertEquals(app.cursor().get(),store.getRootData());
			}
		}
	}

	@Test void convenienceRootRetainsPrelaunchEditsWhenRestoring() throws Exception {
		try (var store=EtchStore.createTemp("root-prelaunch");
				var node=new NodeServer<>(LATTICE,store,NodeConfig.create().withTransactionalRoot(true))) {
			store.setRootData(LATTICE.zero().assoc(ITEMS,Sets.of(CVMLong.ONE)));
			node.mergeValue(LATTICE.zero().assoc(ITEMS,Sets.of(CVMLong.TWO)));
			assertTrue(node.getCursor().supportsExclusiveTransactions());
			node.launch();
			assertEquals(Sets.of(CVMLong.ONE,CVMLong.TWO),node.getLocalValue().get(ITEMS));
			assertEquals(node.getLocalValue(),store.getRootData());
		}
	}

	@Test void networkAcknowledgementDoesNotPublishPrivateApplicationState() throws Exception {
		var config=NodeConfig.localNetwork();
		try (var store=new MemoryStore(); var node=new NodeServer<>(LatticeHost.open(store),config);
				var transport=new LatticeListener(config)) {
			var group=new LatticePropagator(store);
			group.setTransportKeyPair(AKeyPair.createSeeded(9185));
			node.addPropagator(group);
			transport.registerPropagator(group);
			transport.setSelector(connection -> group);
			var app=new App(node.getHost());
			node.getHost().configureApplications(List.of(app));
			node.launch();
			transport.launch();
			try (var peer=ConvexRemote.connect(transport.getHostAddress())) {
				var first=Sets.of(CVMLong.ONE);
				var payload=Vectors.create(MessageTag.LATTICE_VALUE,CVMLong.ONE,Vectors.of(ITEMS),first);
				var result=peer.message(Message.create(MessageType.LATTICE_VALUE,payload)).get(5,TimeUnit.SECONDS);
				assertFalse(result.isError(),result.toString());
				assertEquals(first,app.cursor().get().get(ITEMS));
				assertEquals(LATTICE.zero(),node.getLocalValue());
				assertEquals(LATTICE.zero(),store.getRootData());
				assertEquals(0,app.preparations);
				var announced=group.nextAnnounce();
				app.sync();
				node.getHost().sync();
				assertEquals(app.cursor().get(),announced.get(5,TimeUnit.SECONDS));
			}
		}
	}

	@Test void directAndSnapshotEntryPointsRespectApplicationAcceptance() throws Exception {
		try (var store=new MemoryStore(); var node=new NodeServer<>(LatticeHost.open(store))) {
			var app=new App(node.getHost());
			node.getHost().configureApplications(List.of(app));
			node.launch();
			app.ignore=true;
			var incoming=LATTICE.zero().assoc(ITEMS,Sets.of(CVMLong.ONE));
			node.mergeValue(incoming);
			node.mergeAt(Sets.of(CVMLong.ONE),ITEMS);
			node.persistSnapshot(incoming);
			assertEquals(3,app.merges);
			assertEquals(LATTICE.zero(),node.getLocalValue());
			assertEquals(LATTICE.zero(),store.getRootData());
			app.ignore=false;
			node.mergeAt(Sets.of(CVMLong.ONE),ITEMS);
			assertEquals(incoming,app.cursor().get(),"host schema must not filter application paths");
			assertEquals(0,app.preparations);
		}
	}

	@Test void pullsDeliverToApplicationWithoutForcingPublication() throws Exception {
		try (var store=new MemoryStore(); var node=new NodeServer<>(LatticeHost.open(store))) {
			var incoming=LATTICE.zero().assoc(ITEMS,Sets.of(CVMLong.ONE));
			var group=new LatticePropagator(store) {
				@Override public CompletableFuture<ACell> pull(Convex peer) {
					return CompletableFuture.completedFuture(incoming);
				}
				@Override public CompletableFuture<ACell> pullPath(Convex peer,boolean background,ACell... path) {
					return CompletableFuture.completedFuture(Sets.of(CVMLong.TWO));
				}
				@Override public CompletableFuture<List<ACell>> pullAll() {
					return CompletableFuture.completedFuture(List.of(incoming));
				}
			};
			node.addPropagator(group);
			var app=new App(node.getHost());
			node.getHost().configureApplications(List.of(app));
			node.launch();
			assertEquals(LATTICE.zero(),node.pull(group,(Convex)null).get(5,TimeUnit.SECONDS));
			assertNull(node.pullPath(group,null,ITEMS).get(5,TimeUnit.SECONDS));
			assertTrue(node.pull(group));
			assertEquals(3,app.merges);
			assertEquals(Sets.of(CVMLong.ONE,CVMLong.TWO),app.cursor().get().get(ITEMS));
			assertEquals(LATTICE.zero(),store.getRootData());
			assertEquals(0,app.preparations);
		}
	}
}
