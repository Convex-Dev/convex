package convex.lattice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.Random;

import org.junit.jupiter.api.Test;

import convex.core.data.ABlob;
import convex.core.data.ACell;
import convex.core.data.ASet;
import convex.core.data.Blob;
import convex.core.data.Blobs;
import convex.core.data.Cells;
import convex.core.data.RefSoft;
import convex.core.data.Sets;
import convex.etch.EtchStore;
import convex.lattice.cursor.ALatticeCursor;
import convex.lattice.cursor.Cursors;
import convex.lattice.generic.SetLattice;

public class ALatticeComponentTest {

	private static class TestComponent extends ALatticeComponent<ASet<ACell>> {

		TestComponent(ALatticeCursor<ASet<ACell>> cursor) {
			super(cursor);
		}

		TestComponent(ALatticeComponent<?> parent, ALatticeCursor<ASet<ACell>> cursor) {
			super(parent,cursor);
		}

		void add(ACell value) {
			cursor.updateAndGet(set->set.include(value));
		}
	}

	private static class StoredComponent extends TestComponent {

		private final EtchStore store;
		private int persistCount;

		StoredComponent(ALatticeCursor<ASet<ACell>> cursor, EtchStore store) {
			super(cursor);
			this.store=store;
		}

		@Override
		protected <T extends ACell> T persist(T value) throws IOException {
			persistCount++;
			return Cells.persist(value,store);
		}
	}

	private static ALatticeCursor<ASet<ACell>> createCursor() {
		return Cursors.createLattice(SetLattice.create(),Sets.empty());
	}

	@Test
	public void testStandalonePersistenceIsIdentity() throws IOException {
		TestComponent component=new TestComponent(createCursor());
		Blob value=Blob.fromHex("cafebabe");
		component.add(value);

		assertSame(component.cursor().get(),component.persist());
	}

	@Test
	public void testPersistenceDelegatesToParentWithoutChangingCursor() throws IOException {
		try (EtchStore store=EtchStore.createTemp("lattice-component")) {
			StoredComponent root=new StoredComponent(createCursor(),store);
			TestComponent child=new TestComponent(root,root.cursor().fork());
			ABlob value=Blobs.createRandom(new Random(1234),5000);
			child.add(value);

			ASet<ACell> before=child.cursor().get();
			ASet<ACell> persisted=child.persist();

			assertEquals(1,root.persistCount);
			assertSame(before,child.cursor().get());
			assertEquals(before,persisted);
			ABlob persistedBlob=(ABlob)persisted.getRef(0).getValue();
			assertInstanceOf(RefSoft.class,persistedBlob.getRef(0));
		}
	}

	@Test
	public void testForkCanPersistWithoutSyncing() throws IOException {
		try (EtchStore store=EtchStore.createTemp("lattice-component-fork")) {
			StoredComponent root=new StoredComponent(createCursor(),store);
			TestComponent fork=new TestComponent(root,root.cursor().fork());
			Blob value=Blob.fromHex("cafebabe");
			fork.add(value);

			ASet<ACell> persisted=fork.persist();

			assertTrue(persisted.contains(value));
			assertFalse(root.cursor().get().contains(value));

			fork.sync();
			assertTrue(root.cursor().get().contains(value));
		}
	}
}
