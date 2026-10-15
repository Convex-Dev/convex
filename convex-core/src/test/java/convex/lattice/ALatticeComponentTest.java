package convex.lattice;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import convex.core.data.ACell;
import convex.core.data.ASet;
import convex.core.data.Blob;
import convex.core.store.MemoryStore;
import convex.lattice.cursor.AForkableCursor;
import convex.lattice.cursor.Cursors;
import convex.lattice.generic.SetLattice;

public class ALatticeComponentTest {

	private static final SetLattice<ACell> LATTICE=SetLattice.create();

	/** A component is a cursor plus domain operations; nothing else is inherited. */
	private static class TestComponent extends ALatticeComponent<ASet<ACell>> {

		TestComponent(AForkableCursor<ASet<ACell>> cursor) {
			super(cursor);
		}

		TestComponent fork() {
			return new TestComponent(convex.lattice.ComponentTestSupport.fork(cursor,LATTICE));
		}

		void add(ACell value) {
			cursor.updateAndGet(set->set.include(value));
		}
	}

	@Test
	public void testStandaloneComponentOverCursor() {
		TestComponent component=new TestComponent(Cursors.root(LATTICE.zero()));
		Blob value=Blob.fromHex("cafebabe");
		component.add(value);
		assertTrue(component.cursor().get().contains(value));
	}

	@Test
	public void testForkSyncsThroughCursorWithoutPublishing() throws Exception {
		MemoryStore store=new MemoryStore();
		var host=RootComponent.create(LATTICE,store);
		TestComponent live=new TestComponent(host.cursor());
		TestComponent fork=live.fork();
		Blob value=Blob.fromHex("cafebabe");
		fork.add(value);

		assertTrue(fork.cursor().get().contains(value));
		assertFalse(host.cursor().get().contains(value));

		fork.sync();
		assertTrue(host.cursor().get().contains(value));
		assertNull(store.getRootData(),"Merging a fork into the live cursor must not publish the root");
	}
}
