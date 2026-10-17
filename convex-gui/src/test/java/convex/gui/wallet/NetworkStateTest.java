package convex.gui.wallet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.EventQueue;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import convex.api.ConvexLocal;
import convex.api.ConvexRemote;
import convex.core.crypto.AKeyPair;
import convex.core.cvm.Address;
import convex.core.cvm.Keywords;
import convex.core.cvm.Migrations;
import convex.core.cvm.State;
import convex.core.data.Keyword;
import convex.core.init.Init;
import convex.core.lang.RT;
import convex.core.store.MemoryStore;
import convex.peer.API;
import convex.peer.Server;

/**
 * Tests that wallet network state is kept per network. Needs no display.
 */
public class NetworkStateTest {

	/** Launches a peer on a fresh network of its own, in memory, on a random port */
	private static Server launchNetwork(MemoryStore store) throws Exception {
		AKeyPair kp=AKeyPair.generate();
		State genesis=Migrations.applyAll(Init.createState(List.of(kp.getAccountKey())));
		Map<Keyword,Object> config=new HashMap<>();
		config.put(Keywords.KEYPAIR, kp);
		config.put(Keywords.STATE, genesis);
		config.put(Keywords.STORE, store);
		config.put(Keywords.PORT, 0);
		config.put(Keywords.AUTO_MANAGE, false);
		config.put(Keywords.OUTGOING_CONNECTIONS, 0);
		return API.launchPeer(config);
	}

	@Test
	public void testNetworksAreSeparate() throws Exception {
		try (MemoryStore storeA=new MemoryStore(); MemoryStore storeB=new MemoryStore();
			 Server a=launchNetwork(storeA); Server b=launchNetwork(storeB);
			 ConvexLocal localA=ConvexLocal.connect(a); ConvexLocal localB=ConvexLocal.connect(b)) {

			NetworkState netA=NetworkState.of(localA);
			NetworkState netB=NetworkState.of(localB);
			assertNotSame(netA, netB);
			assertEquals(a.getPeer().getGenesisHash(), netA.getGenesis());
			assertSame(netA, NetworkState.forGenesis(netA.getGenesis()));

			// A remote connection finds its network from the peer status
			try (ConvexRemote remoteA=ConvexRemote.connect(a.getHostAddress())) {
				assertSame(netA, NetworkState.of(remoteA));
			}

			// Library addresses are resolved, and cached, for each network
			Address fungible=netA.resolveLibrary(localA, "convex.fungible");
			assertNotNull(fungible);
			assertEquals(RT.ensureAddress(localA.resolve("convex.fungible").get()), fungible);
			assertSame(fungible, netA.resolveLibrary(localA, "convex.fungible"));

			// A tracked token belongs to one network only
			TokenInfo token=TokenInfo.forID(fungible);
			netA.track(token);
			boolean[] tracked=new boolean[2];
			EventQueue.invokeAndWait(()->{
				tracked[0]=netA.isTracked(token);
				tracked[1]=netB.isTracked(token);
			});
			assertTrue(tracked[0]);
			assertFalse(tracked[1]);

			netA.untrack(token);
			EventQueue.invokeAndWait(()->tracked[0]=netA.isTracked(token));
			assertFalse(tracked[0]);
		}
	}
}
