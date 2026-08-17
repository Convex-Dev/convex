package convex.lattice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.Random;

import org.junit.jupiter.api.Test;

import convex.core.data.ABlob;
import convex.core.data.ACell;
import convex.core.data.ASet;
import convex.core.data.Blobs;
import convex.core.data.RefSoft;
import convex.etch.EtchStore;
import convex.lattice.generic.SetLattice;

public class RootComponentTest {

	@Test
	public void testLocalStorePersistenceDoesNotMoveCursor() throws Exception {
		try (EtchStore store=EtchStore.createTemp("root-component")) {
			RootComponent<ASet<ACell>> root=RootComponent.create(SetLattice.create(),store);
			ABlob blob=Blobs.createRandom(new Random(1234),5000);
			root.cursor().updateAndGet(values->values.include(blob));
			ASet<ACell> before=root.cursor().get();

			ASet<ACell> persisted=root.persist();

			assertSame(before,root.cursor().get(),"store persistence must not move the cursor");
			assertEquals(before,persisted);
			ABlob persistedBlob=(ABlob)persisted.getRef(0).getValue();
			assertInstanceOf(RefSoft.class,persistedBlob.getRef(0));
			assertSame(store,root.store());
		}
	}
}
