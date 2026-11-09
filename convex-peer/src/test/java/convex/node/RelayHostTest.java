package convex.node;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import convex.lattice.LatticeHost;
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
		try (MemoryStore store = new MemoryStore()) {
			assertThrows(NullPointerException.class, () -> LatticeHost.replace((CVMLong)null, store));
			assertNull(store.getRootData());
			assertNull(LatticeHost.open((CVMLong)null, store).cursor().get(),
					"A null fallback remains valid when opening an empty store");

			store.setRootData(CVMLong.ONE);
			assertThrows(NullPointerException.class, () -> LatticeHost.replace((CVMLong)null, store));
			assertEquals(CVMLong.ONE, store.getRootData(), "Rejected replacement must preserve retained state");
			assertEquals(CVMLong.ONE, LatticeHost.open((CVMLong)null, store).cursor().get());

			var replacement = LatticeHost.replace(CVMLong.TWO, store);
			assertEquals(CVMLong.TWO, replacement.cursor().get());
			assertEquals(CVMLong.TWO, store.getRootData(), "Valid replacement writes the root immediately");
		}
	}
}
