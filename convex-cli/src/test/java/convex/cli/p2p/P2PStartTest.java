package convex.cli.p2p;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import convex.cli.CLIError;
import convex.cli.CLTester;
import convex.cli.ExitCodes;
import convex.core.crypto.AKeyPair;
import convex.core.data.AccountKey;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.node.NodeConfig;
import convex.p2p.P2PNode;

public class P2PStartTest {

	@Test
	public void testSeedOptionIsAKeyAndAURL() {
		AccountKey key=AKeyPair.createSeeded(1).getAccountKey();
		var entry=P2PStart.seedEntry("0x"+key.toHexString()+"@tcp://peer.example.com:18888");
		var seeds=P2PNode.seeds(NodeConfig.create(Maps.of(P2PNode.SEEDS,Vectors.of(entry))));
		assertEquals(List.of(key),List.copyOf(seeds.keySet()));
		assertEquals("peer.example.com",seeds.get(key).getHostString());

		assertThrows(CLIError.class,() -> P2PStart.seedEntry("tcp://peer.example.com:18888"));
		assertThrows(CLIError.class,() -> P2PStart.seedEntry("0x1234@"));
		assertTrue(entry.get(Strings.create("url")).toString().startsWith("tcp://"));
	}

	@Test
	public void testInvalidSeedsFailBeforeStarting() {
		assertEquals(ExitCodes.USAGE,CLTester.run("p2p","start","--seed","peer.example.com").getResult());
		assertEquals(ExitCodes.CONFIG,CLTester.run("p2p","start","--seed","0x1234@tcp://localhost:1").getResult());
	}

	@Test
	public void testPersistentStoreRequiresStableKey() {
		assertEquals(ExitCodes.CONFIG,CLTester.run("p2p","start","--etch","unused.etch").getResult());
	}
}
