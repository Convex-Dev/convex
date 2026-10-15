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
import convex.lattice.RootComponent;
import convex.lattice.generic.KeyedLattice;
import convex.lattice.generic.SetLattice;

class NodeApplicationIngressTest {
	private static final Keyword ITEMS=Keyword.intern("items");
	private static final KeyedLattice LATTICE=KeyedLattice.create(ITEMS,SetLattice.create());
	private static class App extends ALatticeApplication<Index<Keyword,ACell>> {
		int preparations;
		int merges;
		boolean ignore;
		App(RootComponent<Index<Keyword,ACell>> host) { super(LATTICE,host); }
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

	@Test void factorySeesRestoredStateBeforePropagationStarts() throws Exception {
		try (var store=EtchStore.createTemp("application-launch"); var node=new NodeServer<>(LATTICE,store)) {
			var retained=LATTICE.zero().assoc(ITEMS,Sets.of(CVMLong.ONE));
			store.setRootData(retained);
			var app=node.launch(root -> {
				assertEquals(retained,root.cursor().get());
				assertFalse(node.isRunning());
				return new App(root);
			});
			assertEquals(retained,app.cursor().get());
			node.mergeAt(Sets.of(CVMLong.TWO),ITEMS);
			assertEquals(Sets.of(CVMLong.ONE,CVMLong.TWO),app.cursor().get().get(ITEMS));
			assertEquals(retained,node.getLocalValue());
			assertEquals(0,app.preparations);
			assertThrows(IllegalStateException.class,() -> node.launch(App::new));
		}
	}

	@Test void restoreFailureDoesNotConstructApplicationOrReplaceRoot() throws Exception {
		try (var store=new MemoryStore() {
			@Override public boolean isPersistent() { return true; }
			@Override public <T extends ACell> T getRootData() throws IOException {
				throw new IOException("unreadable retained root");
			}
		}; var node=new NodeServer<>(LATTICE,store)) {
			var failure=assertThrows(IOException.class,() -> node.launch(root -> {
				fail("Application must not be constructed after a failed restore");
				return new App(root);
			}));
			assertEquals("unreadable retained root",failure.getMessage());
			assertFalse(node.isRunning());
			assertEquals(NodeServer.LifecycleState.STOPPED,node.getLifecycleState());
			assertNull(store.getRootHash());
		}
	}

	@Test void suppliedRootKeepsInitialisationContextAndCursorPolicy() throws Exception {
		try (var store=EtchStore.createTemp("supplied-root")) {
			var retained=LATTICE.zero().assoc(ITEMS,Sets.of(CVMLong.ONE));
			store.setRootData(retained);
			var root=RootComponent.open(LATTICE,store);
			var context=LatticeContext.EMPTY.withMaxFutureTimestampSkew(1234L);
			root.setContext(context);
			// A supplied root is already initialised: launch must not reload the store.
			store.setRootData(LATTICE.zero().assoc(ITEMS,Sets.of(CVMLong.TWO)));
			try (var node=new NodeServer<>(root,NodeConfig.create().withTransactionalRoot(true))) {
				assertSame(root,node.getRootComponent());
				assertSame(root.cursor(),node.getCursor());
				assertSame(store,node.getStore());
				assertSame(context,node.getMergeContext());
				assertFalse(node.getCursor().supportsExclusiveTransactions());
				var app=node.launch(App::new);
				assertSame(context,app.getContext());
				assertEquals(retained,app.cursor().get());
				assertEquals(retained,store.getRootData());
			}
		}
	}

	@Test void suppliedRootDelegatesToAlreadyConfiguredApplications() throws Exception {
		try (var store=new MemoryStore()) {
			var root=RootComponent.create(KeyedLattice.create(),store);
			var app=new App(root);
			root.configureApplications(List.of(app));
			try (var node=new NodeServer<>(root)) {
				node.launch();
				var incoming=LATTICE.zero().assoc(ITEMS,Sets.of(CVMLong.ONE));
				node.mergeValue(incoming);
				node.mergeAt(Sets.of(CVMLong.TWO),ITEMS);
				assertEquals(2,app.merges);
				assertEquals(Sets.of(CVMLong.ONE,CVMLong.TWO),app.cursor().get().get(ITEMS));
				assertEquals(root.getLattice().zero(),node.getLocalValue());
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
			var app=node.launch(App::new);
			assertEquals(Sets.of(CVMLong.ONE,CVMLong.TWO),app.cursor().get().get(ITEMS));
			assertEquals(app.cursor().get(),store.getRootData());
			assertEquals(0,app.preparations);
		}
	}

	@Test void networkAcknowledgementDoesNotPublishPrivateApplicationState() throws Exception {
		var config=NodeConfig.localNetwork();
		try (var store=new MemoryStore(); var node=new NodeServer<>(LATTICE,store,config);
				var transport=new LatticeListener(config)) {
			var group=new LatticePropagator(store,LATTICE,value -> value);
			group.setTransportKeyPair(AKeyPair.createSeeded(9185));
			node.addPropagator(group);
			transport.registerPropagator(group);
			transport.setSelector(connection -> group);
			var app=node.launch(App::new);
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
				node.getRootComponent().sync();
				assertEquals(app.cursor().get(),announced.get(5,TimeUnit.SECONDS));
			}
		}
	}

	@Test void inboundDefersPublicationAndShutdownDoesNotPublishDrafts() throws Exception {
		try (var store=new MemoryStore(); var node=new NodeServer<>(LATTICE,store)) {
			node.launch();
			var app=new App(node.getRootComponent());
			node.getRootComponent().configureApplications(List.of(app));
			var first=Sets.of(CVMLong.ONE);
			var outcome=node.mergeInbound(new ACell[]{ITEMS},first);
			assertTrue(outcome.accepted());
			assertFalse(outcome.changed(),"changed reports published host state, not application drafts");
			assertEquals(first,app.cursor().get().get(ITEMS));
			assertEquals(0,app.preparations);
			assertEquals(LATTICE.zero(),store.getRootData());
			app.sync();
			assertEquals(LATTICE.zero(),store.getRootData());
			node.getRootComponent().sync();
			var published=node.getLocalValue();
			assertEquals(published,store.getRootData());
			node.mergeInbound(new ACell[]{ITEMS},Sets.of(CVMLong.TWO));
			assertEquals(Sets.of(CVMLong.ONE,CVMLong.TWO),app.cursor().get().get(ITEMS));
			node.close();
			assertEquals(published,store.getRootData());
			assertEquals(1,app.preparations);
		}
	}

	@Test void directAndSnapshotEntryPointsRespectApplicationAcceptance() throws Exception {
		try (var store=new MemoryStore(); var node=new NodeServer<>(KeyedLattice.create(),store)) {
			node.launch();
			var app=new App(node.getRootComponent());
			node.getRootComponent().configureApplications(List.of(app));
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
		try (var store=new MemoryStore(); var node=new NodeServer<>(LATTICE,store)) {
			var incoming=LATTICE.zero().assoc(ITEMS,Sets.of(CVMLong.ONE));
			var group=new LatticePropagator(store,LATTICE,value -> value) {
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
			node.launch();
			var app=new App(node.getRootComponent());
			node.getRootComponent().configureApplications(List.of(app));
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
