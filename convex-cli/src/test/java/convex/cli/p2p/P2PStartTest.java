package convex.cli.p2p;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.URI;
import java.util.List;

import org.junit.jupiter.api.Test;

import convex.cli.CLIError;
import convex.cli.CLTester;
import convex.cli.ExitCodes;
import convex.core.crypto.AKeyPair;
import convex.core.data.AccountKey;
import convex.core.data.Maps;
import convex.core.data.Vectors;
import convex.net.PeerName;
import convex.node.NodeConfig;
import convex.p2p.P2PNode;

public class P2PStartTest {

	@Test
	public void testSeedOptionIsAPeerName() {
		AccountKey key=AKeyPair.createSeeded(1).getAccountKey();
		var keyed=P2PStart.seedEntry("0x"+key.toHexString()+"@tcp://peer.example.com:18888");
		var open=P2PStart.seedEntry("did:web:files.example.com");
		assertEquals(List.of(new PeerName(URI.create("tcp://peer.example.com:18888"),key),
				new PeerName(URI.create("did:web:files.example.com"),null)),
			P2PNode.seeds(NodeConfig.create(Maps.of(P2PNode.SEEDS,Vectors.of(keyed,open)))));

		assertThrows(CLIError.class,() -> P2PStart.seedEntry("did:convex:13"));
		assertThrows(CLIError.class,() -> P2PStart.seedEntry("0x1234@tcp://peer.example.com:18888"));
		assertThrows(CLIError.class,() -> P2PStart.seedEntry("0x1234@"));
	}

	@Test
	public void testInvalidSeedsFailBeforeStarting() {
		assertEquals(ExitCodes.USAGE,CLTester.run("p2p","start","--seed","did:convex:13").getResult());
		assertEquals(ExitCodes.USAGE,CLTester.run("p2p","start","--seed","0x1234@tcp://localhost:1").getResult());
	}

	@Test
	public void testPersistentStoreRequiresStableKey() {
		assertEquals(ExitCodes.CONFIG,CLTester.run("p2p","start","--etch","unused.etch").getResult());
	}
}
