package convex.node;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import java.util.function.Function;
import convex.lattice.LatticeHost;
import convex.lattice.cursor.Cursors;
import convex.lattice.cursor.Root;
import convex.core.data.prim.CVMLong;
import convex.core.store.MemoryStore;

public class RelayHostTest {
	@Test public void storageFlagsAreRejectedInsteadOfSilentlyChangingDurability() {
		for (String key:new String[]{"persist","persistInterval","restore","transactionalRoot"}) {
			assertThrows(IllegalArgumentException.class,() -> NodeConfig.create(
				convex.core.data.Maps.of(convex.core.data.Strings.create(key),convex.core.data.prim.CVMBool.FALSE)));
		}
	}

	@Test
	public void testNullReplacementRejected() throws Exception {
		checkNullReplacement(false);
		checkNullReplacement(true);
	}

	private void checkNullReplacement(boolean transactional) throws Exception {
		Function<CVMLong,Root<CVMLong>> factory=transactional?Cursors::transactionalRoot:Cursors::root;
		try (MemoryStore store = new MemoryStore()) {
			assertThrows(NullPointerException.class, () -> LatticeHost.replace(null, store, factory));
			assertNull(store.getRootData());
			assertNull(LatticeHost.open(null, store, factory).cursor().get(),
					"A null fallback remains valid when opening an empty store");

			store.setRootData(CVMLong.ONE);
			assertThrows(NullPointerException.class, () -> LatticeHost.replace(null, store, factory));
			assertEquals(CVMLong.ONE, store.getRootData(), "Rejected replacement must preserve retained state");
			assertEquals(CVMLong.ONE, LatticeHost.open(null, store, factory).cursor().get());

			var replacement = LatticeHost.replace(CVMLong.TWO, store, factory);
			assertEquals(transactional,replacement.cursor().supportsExclusiveTransactions());
			assertEquals(CVMLong.TWO, replacement.cursor().get());
			assertEquals(CVMLong.TWO, store.getRootData(), "Valid replacement writes the root immediately");
		}
	}
}
