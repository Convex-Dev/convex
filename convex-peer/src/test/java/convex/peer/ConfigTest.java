package convex.peer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
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
import convex.core.store.MemoryStore;
import convex.etch.EtchConfig;
import convex.etch.EtchConfig.CipherMode;
import convex.etch.EtchConfig.MappingMode;
import convex.etch.EtchConstants;
import convex.etch.EtchStore;

public class ConfigTest {
	
	@Test public void testStoreSetup() throws ConfigException {
		// Empty config should create a default new store
		HashMap<Keyword,Object> config=new HashMap<>();
		EtchStore store=Config.ensureStore(config);
		assertNotNull(store);
		
		assertTrue(store.getFile().exists());
		store.close();
	}

	@Test public void testConfiguredEtchStore() throws Exception {
		byte[] secret=new byte[32];
		for (int i=0;i<secret.length;i++) secret[i]=(byte)i;
		EtchConfig etchConfig=EtchConfig.createV3(MappingMode.MAPPED_BYTE_BUFFER,true,
				CipherMode.AES_256_CTR,true,null,hint->secret.clone());
		HashMap<Keyword,Object> config=new HashMap<>();
		config.put(Keywords.STORE,"temp");
		config.put(Keywords.ETCH_CONFIG,etchConfig);

		try (EtchStore store=Config.checkStore(config)) {
			assertEquals(EtchConstants.VERSION_3,store.getEtch().getConfig().getVersion());
			assertEquals(CipherMode.AES_256_CTR,store.getEtch().getConfig().getCipherMode());
			assertTrue(store.getEtch().getConfig().isIndexEncrypted());
		}
	}

	@Test public void testInvalidEtchConfigFailsClosed() {
		HashMap<Keyword,Object> config=new HashMap<>();
		config.put(Keywords.STORE,"temp");
		config.put(Keywords.ETCH_CONFIG,"not compiled");
		assertThrows(IOException.class,()->Config.checkStore(config));
	}

	@Test public void testWrongEtchKeyIsNotReplacedByTemporaryStore() throws Exception {
		File file=File.createTempFile("peer-encrypted", ".etch");
		file.deleteOnExit();
		EtchConfig correct=encryptedConfig((byte)0x40);
		try (EtchStore store=EtchStore.create(file,correct)) {
			store.flush();
		}
		long length=file.length();

		HashMap<Keyword,Object> config=new HashMap<>();
		config.put(Keywords.STORE,file.getPath());
		config.put(Keywords.ETCH_CONFIG,encryptedConfig((byte)0x41));
		assertThrows(IOException.class,()->Config.checkStore(config));
		assertEquals(length,file.length());
	}

	@Test public void testExistingEncryptedStoreUsesConfiguredPeerKey() throws Exception {
		AKeyPair keyPair=AKeyPair.generate();
		File file=File.createTempFile("peer-key-default", ".etch");
		file.deleteOnExit();
		EtchConfig encrypted=EtchConfig.createV3(MappingMode.MAPPED_BYTE_BUFFER,true,
				CipherMode.AES_256_CTR,true,keyPair.getAccountKey(),Config.etchKeyResolver(keyPair));
		try (EtchStore store=EtchStore.create(file,encrypted)) {
			store.flush();
		}

		HashMap<Keyword,Object> config=new HashMap<>();
		config.put(Keywords.STORE,file.getPath());
		config.put(Keywords.KEYPAIR,keyPair);
		try (EtchStore store=Config.checkStore(config)) {
			assertEquals(keyPair.getAccountKey(),store.getEtch().getConfig().getPublicKeyHint());
			assertEquals(CipherMode.AES_256_CTR,store.getEtch().getConfig().getCipherMode());
		}
	}

	@Test public void testConfiguredStorePathIsCreated() throws Exception {
		File file=File.createTempFile("configured-peer-store", ".etch");
		assertTrue(file.delete());
		file.deleteOnExit();
		HashMap<Keyword,Object> config=new HashMap<>();
		config.put(Keywords.STORE,file.getPath());
		try (EtchStore store=Config.checkStore(config)) {
			assertEquals(file.getCanonicalFile(),store.getFile().getCanonicalFile());
		}
	}

	@Test public void testExplicitEtchResolverOverridesPeerKeyDefault() throws Exception {
		AKeyPair peerKey=AKeyPair.generate();
		AccountKey storageKey=AccountKey.dummy("57a9");
		byte[] secret=new byte[32];
		secret[0]=0x5a;
		File file=File.createTempFile("peer-explicit-resolver", ".etch");
		file.deleteOnExit();
		EtchConfig encrypted=EtchConfig.createV3(MappingMode.MAPPED_BYTE_BUFFER,true,
				CipherMode.CHACHA20,true,storageKey,ignored->secret.clone());
		try (EtchStore store=EtchStore.create(file,encrypted)) {
			store.flush();
		}

		HashMap<Keyword,Object> config=new HashMap<>();
		config.put(Keywords.STORE,file.getPath());
		config.put(Keywords.KEYPAIR,peerKey);
		config.put(Config.ETCH_KEY_RESOLVER,
				(java.util.function.Function<AccountKey,byte[]>)(ignored->secret.clone()));
		try (EtchStore store=Config.checkStore(config)) {
			assertEquals(storageKey,store.getEtch().getConfig().getPublicKeyHint());
			assertEquals(CipherMode.CHACHA20,store.getEtch().getConfig().getCipherMode());
		}
	}

	@Test public void testInvalidEtchResolverFailsClosed() {
		HashMap<Keyword,Object> config=new HashMap<>();
		config.put(Config.ETCH_KEY_RESOLVER,"not a resolver");
		assertThrows(IOException.class,()->Config.getEtchConfig(config));
	}

	private static EtchConfig encryptedConfig(byte first) {
		byte[] secret=new byte[32];
		secret[0]=first;
		return EtchConfig.createV3(MappingMode.MAPPED_BYTE_BUFFER,true,
				CipherMode.AES_256_CTR,true,null,hint->secret.clone());
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

	@Test public void testServerOwnsImplicitTemporaryStore() throws Exception {
		Map<Keyword,Object> config=Config.of(Keywords.KEYPAIR,AKeyPair.generate());
		Server server=API.launchPeer(config);
		EtchStore store=(EtchStore)server.getStore();
		File file=store.getFile();
		assertTrue(file.exists());

		server.close();
		assertThrows(IOException.class,store::flush);
	}

	@Test public void testServerDoesNotCloseCallerStore() throws Exception {
		TrackingMemoryStore store=new TrackingMemoryStore();
		Map<Keyword,Object> config=Config.of(
				Keywords.KEYPAIR,AKeyPair.generate(),
				Keywords.STORE,store);
		Server server=API.launchPeer(config);

		server.close();
		assertEquals(0,store.closeCount);
		store.close();
		assertEquals(1,store.closeCount);
	}

	@Test public void testServerClosesButPreservesConfiguredStoreFile() throws Exception {
		File file=File.createTempFile("owned-peer-store",".etch");
		assertTrue(file.delete());
		file.deleteOnExit();
		Map<Keyword,Object> config=Config.of(
				Keywords.KEYPAIR,AKeyPair.generate(),
				Keywords.STORE,file.getPath());

		Server server=API.launchPeer(config);
		server.close();
		assertTrue(file.exists());
		try (EtchStore ignored=EtchStore.create(file)) {
			// Reopening proves that Server.close released its owned file lock.
		}
	}

	private static final class TrackingMemoryStore extends MemoryStore {
		private int closeCount;

		@Override
		public void close() {
			closeCount++;
			super.close();
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
