package convex.gui.wallet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import convex.api.ConvexLocal;
import convex.core.Result;
import convex.core.crypto.AKeyPair;
import convex.core.cvm.Address;
import convex.core.cvm.Keywords;
import convex.core.cvm.Migrations;
import convex.core.cvm.State;
import convex.core.data.Keyword;
import convex.core.data.prim.CVMLong;
import convex.core.init.Init;
import convex.core.store.MemoryStore;
import convex.gui.utils.Async;
import convex.peer.API;
import convex.peer.Server;

/**
 * Tests for token transfers. Needs no display.
 */
public class TransferPanelTest {

	/** Launches a peer on a fresh network of its own, in memory, on a random port */
	private static Server launchNetwork(MemoryStore store, AKeyPair kp) throws Exception {
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

	/** A transfer started on the EDT, as the Transfer button starts it, completes and moves the funds */
	@Test
	public void testTransferFromDispatchThread() throws Exception {
		AKeyPair kp=AKeyPair.generate();
		try (MemoryStore store=new MemoryStore(); Server server=launchNetwork(store, kp);
			 ConvexLocal convex=ConvexLocal.connect(server, server.getPeerController(), kp)) {
			Address target=convex.createAccountSync(AKeyPair.generate().getAccountKey());
			long before=convex.getBalance(target);

			TransferPanel panel=CompletableFuture.supplyAsync(()->new TransferPanel(convex, TokenInfo.convexCoin()), Async.EDT).get();
			try {
				Result r=CompletableFuture.supplyAsync(()->panel.executeTransfer(CVMLong.create(1000), target), Async.EDT)
					.thenCompose(transfer->transfer)
					.get(10, TimeUnit.SECONDS);
				assertFalse(r.isError(), ()->"Transfer failed: "+r);
				assertEquals(before+1000, convex.getBalance(target));
			} finally {
				panel.close();
			}
		}
	}
}
