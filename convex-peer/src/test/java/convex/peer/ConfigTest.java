package convex.peer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import convex.core.crypto.AKeyPair;
import convex.core.cvm.Keywords;
import convex.core.cvm.Migrations;
import convex.core.cvm.Peer;
import convex.core.cvm.State;
import convex.core.data.AccountKey;
import convex.core.data.Keyword;
import convex.core.init.Init;
import convex.etch.EtchStore;

public class ConfigTest {
	
	@Test public void testStoreSetup() throws ConfigException {
		// Empty config should create a default new store
		HashMap<Keyword,Object> config=new HashMap<>();
		EtchStore store=Config.ensureStore(config);
		assertNotNull(store);
		
		assertTrue(store.getFile().exists());
	}
	
	@Test public void testKeypair() throws ConfigException {
		// Empty config should create a default new store
		HashMap<Keyword,Object> config=new HashMap<>();
	
		assertThrows(ConfigException.class, ()->Config.ensurePeerKey(config));
		
		AKeyPair kp=AKeyPair.generate();
		config.put(Keywords.KEYPAIR,kp);
		assertSame(kp,Config.ensurePeerKey(config));
		
		// Corrupt the key
		config.put(Keywords.KEYPAIR,1L);
		assertThrows(ConfigException.class, ()->Config.ensurePeerKey(config));
	}
	
	@Test public void testNullLaunch() {
		assertThrows(ConfigException.class,()->API.launchPeer(new HashMap<>()));
		assertThrows(NullPointerException.class,()->API.launchPeer(null));
	}
	
	@Test public void testMinimalLaunch() throws InterruptedException, PeerException {
		AKeyPair kp=AKeyPair.generate();
		AccountKey peerKey=kp.getAccountKey();

		{ // just a peer keypair
			Map<Keyword,Object> config=Config.of(Keywords.KEYPAIR,kp);
			Server s=API.launchPeer(config);
			assertSame(kp,s.getKeyPair());
			Peer p=s.getPeer();
			assertTrue(p.getConsensusState().getPeers().containsKey(peerKey));
			assertEquals(0,p.getFinalityPoint());
			// Fresh network defaults to the latest supported protocol version
			assertEquals(Migrations.MAX_VERSION,p.getConsensusState().getProtocolVersion());
			s.close();
		}
	}

	@Test public void testGenesisProtocolConfig() throws ConfigException {
		AKeyPair kp=AKeyPair.generate();
		State genesis=Init.createState(List.of(kp.getAccountKey()));
		assertEquals(0,genesis.getProtocolVersion());

		// Default (no :protocol-version): latest supported version
		assertEquals(Migrations.MAX_VERSION,
				Config.applyGenesisProtocol(genesis,new HashMap<>()).getProtocolVersion());
		assertEquals(Migrations.MAX_VERSION,
				Config.applyGenesisProtocol(genesis,null).getProtocolVersion());

		// Pinned to 0: the raw genesis, bit-identical
		HashMap<Keyword,Object> config=new HashMap<>();
		config.put(Keywords.PROTOCOL_VERSION,0L);
		assertSame(genesis,Config.applyGenesisProtocol(genesis,config));

		// Out-of-range or non-integer pins fail closed
		config.put(Keywords.PROTOCOL_VERSION,-1L);
		assertThrows(ConfigException.class,()->Config.applyGenesisProtocol(genesis,config));
		config.put(Keywords.PROTOCOL_VERSION,Migrations.MAX_VERSION+1);
		assertThrows(ConfigException.class,()->Config.applyGenesisProtocol(genesis,config));
		config.put(Keywords.PROTOCOL_VERSION,"nope");
		assertThrows(ConfigException.class,()->Config.applyGenesisProtocol(genesis,config));
	}

	@Test public void testPinnedProtocolLaunch() throws InterruptedException, PeerException {
		// A network pinned to protocol version 0 launches with the raw genesis
		Map<Keyword,Object> config=Config.of(
				Keywords.KEYPAIR,AKeyPair.generate(),
				Keywords.PROTOCOL_VERSION,0L);
		Server s=API.launchPeer(config);
		assertEquals(0L,s.getPeer().getConsensusState().getProtocolVersion());
		s.close();
	}

}
