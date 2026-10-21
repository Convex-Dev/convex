package convex.lattice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import convex.core.data.ACell;
import convex.core.data.ASet;
import convex.core.data.Blob;
import convex.core.data.Index;
import convex.core.data.Keyword;
import convex.core.data.Sets;
import convex.core.store.MemoryStore;
import convex.lattice.cursor.AForkableCursor;
import convex.lattice.cursor.Cursors;

public class ALatticeComponentTest {

	/** A component is a cursor plus domain operations; nothing else is inherited. */
	private static class TestComponent extends ALatticeComponent<ASet<ACell>> {

		TestComponent(AForkableCursor<ASet<ACell>> cursor) {
			super(cursor);
		}

		TestComponent fork() {
			return new TestComponent(this);
		}

		private TestComponent(TestComponent source) { super(source); }

		@Override
		protected ASet<ACell> mergePublication(ASet<ACell> own,ASet<ACell> other) {
			return own.includeAll(other);
		}

		@Override
		public ASet<ACell> merge(ASet<ACell> incoming) {
			return cursor.updateAndGet(own -> own.includeAll(incoming));
		}

		void add(ACell value) {
			cursor.updateAndGet(set->set.include(value));
		}
	}

	@Test
	public void testStandaloneComponentOverCursor() {
		TestComponent component=new TestComponent(Cursors.root(Sets.<ACell>empty()));
		Blob value=Blob.fromHex("cafebabe");
		component.add(value);
		assertTrue(component.cursor().get().contains(value));
	}

	@Test
	public void testForkPublishesOnlyThroughComponentSync() throws Exception {
		MemoryStore store=new MemoryStore();
		var host=LatticeHost.<ASet<ACell>>create(Sets.empty(),store);
		TestComponent live=new TestComponent(host.cursor());
		TestComponent fork=live.fork();
		Blob value=Blob.fromHex("cafebabe");
		fork.add(value);

		assertTrue(fork.cursor().get().contains(value));
		assertFalse(host.cursor().get().contains(value));

		new TestComponent(fork.cursor()).sync();
		assertFalse(host.cursor().get().contains(value));
		fork.sync();
		assertTrue(host.cursor().get().contains(value));
		assertNull(store.getRootData(),"Merging a fork into the live cursor must not publish the root");
	}

	/** Component-defined reconciliation combines concurrent edits without any ALattice implementation. */
	@Test
	public void independentDraftsReconcileAndRepeatedSyncIsInert() {
		var live=new TestComponent(Cursors.root(Sets.empty()));
		var first=live.fork();
		var second=live.fork();
		var a=Blob.fromHex("aa");
		var b=Blob.fromHex("bb");
		first.add(a);
		second.add(b);
		first.sync();
		second.sync();
		assertEquals(Sets.of(a,b),live.cursor().get());
		var published=live.cursor().get();
		assertSame(published,second.sync());
		assertSame(published,live.cursor().get());
	}

	/** Region routing depends only on the child's behaviour, not a parallel lattice tree. */
	@Test
	public void regionsRouteToPlainComponentsAndPreserveUnknownState() {
		var key=Keyword.intern("values");
		var unrelated=Keyword.intern("unrelated");
		var marker=Blob.fromHex("abcd");
		var host=LatticeHost.create(Index.<Keyword,ACell>none().assoc(unrelated,marker),null);
		var live=new TestComponent(host.cursor().<ASet<ACell>>path(key).withDefault(Sets.empty()));
		var draft=live.fork();
		var regions=new Regions(host);
		regions.add(key,draft);
		regions.mergeAt(Sets.of(marker),key);
		assertNull(host.cursor().get().get(key));
		regions.mergeAt(Sets.of(marker),unrelated);
		regions.sync();
		assertEquals(Sets.of(marker),host.cursor().get().get(key));
		assertSame(marker,host.cursor().get().get(unrelated));
	}
}
