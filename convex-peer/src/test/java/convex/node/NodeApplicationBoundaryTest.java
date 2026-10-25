package convex.node;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import convex.core.data.ACell;
import convex.core.data.ASet;
import convex.core.data.Sets;
import convex.core.data.prim.CVMLong;
import convex.core.store.MemoryStore;
import convex.lattice.LatticeApplication;
import convex.lattice.LatticeHost;
import convex.lattice.generic.SetLattice;

/** Internal ingress outcome semantics; public application scenarios live in convex.node.app. */
class NodeApplicationBoundaryTest {
	@Test void networkNodeRequiresACellStore() {
		var host=LatticeHost.create();
		var error=assertThrows(NullPointerException.class,() -> new NodeServer<>(host));
		assertEquals("NodeServer requires a host cell store",error.getMessage());
	}

	private static class App extends LatticeApplication<ASet<CVMLong>> {
		int preparations;
		App(LatticeHost<ASet<CVMLong>> host) { super(SetLattice.create(),host); }
		@Override public ASet<CVMLong> merge(ASet<CVMLong> other) {
			return cursor().updateAndGet(own -> getLattice().merge(getContext(),own,other));
		}
		@Override protected void beforeSync() { preparations++; }
	}

	@Test void inboundDefersPublicationAndShutdownDoesNotPublishDrafts() throws Exception {
		var lattice=SetLattice.<CVMLong>create();
		try (var store=new MemoryStore(); var node=new NodeServer<>(LatticeHost.create(lattice.zero(),store))) {
			var app=new App(node.getHost());
			node.getHost().addApplication(app);
			node.launch();
			var first=Sets.of(CVMLong.ONE);
			var outcome=node.mergeInbound(new ACell[0],first);
			assertTrue(outcome.accepted());
			assertFalse(outcome.changed(),"changed reports published host state, not application drafts");
			assertEquals(first,app.cursor().get());
			assertEquals(0,app.preparations);
			assertEquals(lattice.zero(),store.getRootData());
			app.sync();
			assertEquals(lattice.zero(),store.getRootData());
			node.getHost().sync();
			var published=node.getLocalValue();
			assertEquals(published,store.getRootData());
			node.mergeInbound(new ACell[0],Sets.of(CVMLong.TWO));
			assertEquals(Sets.of(CVMLong.ONE,CVMLong.TWO),app.cursor().get());
			node.close();
			assertEquals(published,store.getRootData());
			assertEquals(1,app.preparations);
		}
	}
}
