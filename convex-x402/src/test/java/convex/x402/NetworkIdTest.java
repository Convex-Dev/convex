package convex.x402;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import convex.core.data.Hash;

public class NetworkIdTest {

	private static final Hash GENESIS = Hash.wrap(new byte[32]);

	@Test
	public void testCanonicalForm() {
		NetworkId nid = NetworkId.create(GENESIS);
		assertEquals("convex:" + "0".repeat(32), nid.canonical());
		assertEquals(GENESIS, nid.getGenesisHash());
	}

	@Test
	public void testMatching() {
		NetworkId nid = NetworkId.create(GENESIS);
		assertTrue(nid.matches(nid.canonical()));
		// Registered aliases all denote this network for now (see CAD042)
		assertTrue(nid.matches("convex:protonet"));
		assertTrue(nid.matches("convex:testnet"));
		assertTrue(nid.matches("convex:main"));
		assertTrue(nid.matches("convex:local"));

		assertFalse(nid.matches("convex:mainnet")); // not a registered alias
		assertFalse(nid.matches("convex:" + "1".repeat(32)));
		assertFalse(nid.matches("eip155:8453"));
		assertFalse(nid.matches("convex"));
		assertFalse(nid.matches(null));
	}

	@Test
	public void testKnownForms() {
		NetworkId nid = NetworkId.create(GENESIS);
		assertEquals(5, nid.knownForms().size());
		assertEquals(nid.canonical(), nid.knownForms().get(0));
	}

	@Test
	public void testNullGenesis() {
		assertThrows(IllegalArgumentException.class, () -> NetworkId.create(null));
	}
}
