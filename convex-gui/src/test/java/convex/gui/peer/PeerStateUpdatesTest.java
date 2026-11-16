package convex.gui.peer;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.awt.EventQueue;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import convex.core.crypto.AKeyPair;
import convex.core.cvm.Keywords;
import convex.core.cvm.Migrations;
import convex.core.cvm.State;
import convex.core.data.Keyword;
import convex.core.init.Init;
import convex.core.store.MemoryStore;
import convex.peer.API;
import convex.peer.Server;

/**
 * Tests for UI updates following a local server's peer state. Needs no display.
 * The tests share one peer: each only registers and closes its own observer.
 */
public class PeerStateUpdatesTest {

	private static MemoryStore store;
	private static Server server;

	/** Launches a peer on a fresh network of its own, in memory, on a random port */
	@BeforeAll
	static void launchPeer() throws Exception {
		store=new MemoryStore();
		AKeyPair kp=AKeyPair.generate();
		State genesis=Migrations.applyAll(Init.createState(List.of(kp.getAccountKey())));
		Map<Keyword,Object> config=new HashMap<>();
		config.put(Keywords.KEYPAIR, kp);
		config.put(Keywords.STATE, genesis);
		config.put(Keywords.STORE, store);
		config.put(Keywords.PORT, 0);
		config.put(Keywords.AUTO_MANAGE, false);
		config.put(Keywords.OUTGOING_CONNECTIONS, 0);
		server=API.launchPeer(config);
	}

	@AfterAll
	static void closePeer() {
		// The server borrows the store, so it closes first
		if (server!=null) server.close();
		if (store!=null) store.close();
	}

	/** Waits until the EDT has run everything posted so far */
	private static void flushEDT() throws Exception {
		EventQueue.invokeAndWait(()->{});
	}

	@Test
	public void testUpdatesOnEDT() throws Exception {
		List<Boolean> runs=Collections.synchronizedList(new ArrayList<>());
		PeerStateUpdates updates=new PeerStateUpdates(server, ()->runs.add(EventQueue.isDispatchThread()));

		// Registering delivers the current state, so one update runs straight away
		flushEDT();
		assertEquals(List.of(true), runs);
		updates.close();
	}

	@Test
	public void testNoUpdatesAfterClose() throws Exception {
		List<Boolean> runs=Collections.synchronizedList(new ArrayList<>());
		// Hold the EDT so the first update is still queued when we close
		EventQueue.invokeAndWait(()->{
			PeerStateUpdates updates=new PeerStateUpdates(server, ()->runs.add(true));
			updates.close();
		});
		flushEDT();
		assertEquals(List.of(), runs);
	}
}
