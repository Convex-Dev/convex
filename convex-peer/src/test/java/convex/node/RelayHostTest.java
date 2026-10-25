package convex.node;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import convex.core.data.Maps;
import convex.core.data.prim.CVMBool;
import convex.core.data.prim.CVMLong;
import convex.core.store.MemoryStore;

public class RelayHostTest {
	@Test
	public void testNullReplacementRejected() throws Exception {
		checkNullReplacement(false);
		checkNullReplacement(true);
	}

	private void checkNullReplacement(boolean transactional) throws Exception {
		NodeConfig restore = NodeConfig.create().withTransactionalRoot(transactional);
		NodeConfig replace = NodeConfig.create(Maps.of(NodeConfig.RESTORE, CVMBool.FALSE))
				.withTransactionalRoot(transactional);
		try (MemoryStore store = new MemoryStore()) {
			assertThrows(NullPointerException.class, () -> RelayHost.openHost(null, store, replace));
			assertNull(store.getRootData());
			assertNull(RelayHost.openHost(null, store, restore).cursor().get(),
					"A null fallback remains valid when opening an empty store");

			store.setRootData(CVMLong.ONE);
			assertThrows(NullPointerException.class, () -> RelayHost.openHost(null, store, replace));
			assertEquals(CVMLong.ONE, store.getRootData(), "Rejected replacement must preserve retained state");
			assertEquals(CVMLong.ONE, RelayHost.openHost(null, store, restore).cursor().get());

			var replacement = RelayHost.openHost(CVMLong.TWO, store, replace);
			assertEquals(CVMLong.TWO, replacement.cursor().get());
			assertEquals(CVMLong.TWO, store.getRootData(), "Valid replacement writes the root immediately");
		}
	}
}
