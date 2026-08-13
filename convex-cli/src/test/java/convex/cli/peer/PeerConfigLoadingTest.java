package convex.cli.peer;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.security.KeyStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import convex.cli.Main;
import convex.core.crypto.AKeyPair;
import convex.core.crypto.PFXTools;
import convex.core.cvm.Keywords;
import convex.core.data.AString;
import convex.core.data.Keyword;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.store.AStore;
import convex.etch.EtchConfig;
import convex.etch.EtchConstants;
import convex.etch.EtchStore;
import convex.peer.Config;
import convex.restapi.RESTConfig;

/** Verifies that CLI peer launch preserves nested REST runtime policy. */
class PeerConfigLoadingTest {

	@TempDir
	Path temporaryDirectory;

	@Test
	void loadsAndAttachesTypedRestConfiguration() throws Exception {
		Path file=temporaryDirectory.resolve("peer-config.json5");
		Files.writeString(file,"{auth:{publicAccess:false},mcp:{enabled:false}}");

		Main main=new Main();
		var parseResult=main.commandLine.parseArgs("peer","--config",file.toString());
		Peer peer=(Peer) parseResult.subcommand().commandSpec().userObject();
		var launchConfig=peer.loadPeerConfig();
		RESTConfig config=(RESTConfig) launchConfig.get(RESTConfig.CONFIG);

		assertFalse(config.isPublicAccess());
		assertFalse(config.isMcpEnabled());
		assertSame(config,launchConfig.get(RESTConfig.CONFIG));
	}

	@Test
	void loadsEtchCreationPolicy() throws Exception {
		Path file=temporaryDirectory.resolve("peer-etch-config.json5");
		Files.writeString(file,"{peer:{store:'peer.etch',etch:{version:3}}}");

		Main main=new Main();
		var parseResult=main.commandLine.parseArgs("peer","--config",file.toString());
		Peer peer=(Peer)parseResult.subcommand().commandSpec().userObject();
		var launchConfig=peer.loadPeerConfig();
		EtchConfig etch=(EtchConfig)launchConfig.get(Keywords.ETCH_CONFIG);

		assertEquals(EtchConstants.VERSION_3,etch.getVersion());
		assertEquals("peer.etch",launchConfig.get(Keywords.STORE));
	}

	@Test
	void peerStartCreatesConfiguredV3StoreAtConfigPath() throws Exception {
		Path store=storePath("configured-v3");
		Path config=config(store,"{version:3}");
		ParsedCommand parsed=parse("start","--config",config.toString());

		try (AStore opened=parsed.command().openPeerStore(parsed.command().loadPeerConfig())) {
			EtchStore etch=(EtchStore)opened;
			assertEquals(store.toFile().getCanonicalFile(),etch.getFile().getCanonicalFile());
			assertEquals(EtchConstants.VERSION_3,etch.getEtch().getVersion());
		}
	}

	@ParameterizedTest
	@ValueSource(strings={"--etch","-e"})
	void explicitEtchOptionOverridesConfigPath(String option) throws Exception {
		Path configured=storePath("configured-"+option.length());
		Path explicit=storePath("explicit-"+option.length());
		Path config=config(configured,"{version:3}");
		ParsedCommand parsed=parse("start","--config",config.toString(),option,explicit.toString());

		try (AStore opened=parsed.command().openPeerStore(parsed.command().loadPeerConfig())) {
			EtchStore etch=(EtchStore)opened;
			assertEquals(explicit.toFile().getCanonicalFile(),etch.getFile().getCanonicalFile());
			assertEquals(EtchConstants.VERSION_3,etch.getEtch().getVersion());
		}
		assertFalse(Files.exists(configured));
	}

	@Test
	void peerGenesisUsesConfiguredStorePolicy() throws Exception {
		Path store=storePath("genesis-v3");
		Path config=config(store,"{version:3}");
		ParsedCommand parsed=parse("genesis","--config",config.toString());

		try (AStore opened=parsed.command().openPeerStore(parsed.command().loadPeerConfig())) {
			assertEquals(EtchConstants.VERSION_3,((EtchStore)opened).getEtch().getVersion());
		}
	}

	@Test
	void peerGenesisWithoutEtchPolicyDefaultsToV2() throws Exception {
		Path store=storePath("genesis-default-v2");
		ParsedCommand parsed=parse("genesis","--etch",store.toString());

		try (AStore opened=parsed.command().openPeerStore(parsed.command().loadPeerConfig())) {
			assertEquals(EtchConstants.VERSION_2,((EtchStore)opened).getEtch().getVersion());
		}
	}

	@Test
	void warnsWhenExistingStoreOverridesCreationPolicy() throws Exception {
		Path store=storePath("existing-v2");
		try (EtchStore ignored=EtchStore.create(store.toFile(),
				EtchConfig.create(EtchConstants.VERSION_2))) {
			// Existing store deliberately differs from requested policy.
		}
		Path config=config(store,"{version:3}");
		ParsedCommand parsed=parse("start","--config",config.toString());

		try (AStore opened=parsed.command().openPeerStore(parsed.command().loadPeerConfig())) {
			assertEquals(EtchConstants.VERSION_2,((EtchStore)opened).getEtch().getVersion());
		}
		assertTrue(parsed.error().toString().contains("Existing Etch store uses v2"));
	}

	@Test
	void encryptedConfigResolvesMasterKeyFromKeystore() throws Exception {
		Path keyFile=temporaryDirectory.resolve("peer-keys.pfx");
		char[] storePassword="store-secret".toCharArray();
		char[] keyPassword="key-secret".toCharArray();
		AKeyPair keyPair=AKeyPair.generate();
		KeyStore keys=PFXTools.createStore(keyFile.toFile(),storePassword);
		PFXTools.setKeyPair(keys,keyPair,keyPassword);
		PFXTools.saveStore(keys,keyFile.toFile(),storePassword);

		Path store=storePath("encrypted-v3");
		String etch="{version:3,cipher:'aes-256-ctr',encryptIndex:true,publicKeyHint:'"
				+keyPair.getAccountKey().toHexString()+"'}";
		Path config=config(store,etch);
		String[] options={"--config",config.toString(),"--keystore",keyFile.toString(),
				"--storepass",new String(storePassword),"--peer-keypass",new String(keyPassword)};
		AString expected=Strings.create("encrypted peer configuration round trip");

		ParsedCommand created=parse("start",options);
		try (AStore opened=created.command().openPeerStore(created.command().loadPeerConfig())) {
			EtchStore etchStore=(EtchStore)opened;
			EtchConfig actual=etchStore.getEtch().getConfig();
			assertEquals(EtchConfig.CipherMode.AES_256_CTR,actual.getCipherMode());
			assertTrue(actual.isIndexEncrypted());
			etchStore.setRootData(expected);
			etchStore.flush();
		}

		ParsedCommand reopened=parse("start",options);
		try (AStore opened=reopened.command().openPeerStore(reopened.command().loadPeerConfig())) {
			EtchStore etchStore=(EtchStore)opened;
			assertEquals(EtchConfig.CipherMode.AES_256_CTR,
					etchStore.getEtch().getConfig().getCipherMode());
			assertEquals(expected,etchStore.getRootData());
		}
	}

	@Test
	void existingEncryptedStoreUsesHeaderHintWithoutEtchPolicy() throws Exception {
		Path keyFile=temporaryDirectory.resolve("hinted-peer-keys.pfx");
		char[] storePassword="store-secret".toCharArray();
		char[] keyPassword="key-secret".toCharArray();
		AKeyPair keyPair=AKeyPair.generate();
		KeyStore keys=PFXTools.createStore(keyFile.toFile(),storePassword);
		PFXTools.setKeyPair(keys,keyPair,keyPassword);
		PFXTools.saveStore(keys,keyFile.toFile(),storePassword);

		Path store=storePath("hinted-existing-v3");
		EtchConfig encrypted=EtchConfig.createV3(EtchConfig.MappingMode.MAPPED_BYTE_BUFFER,true,
				EtchConfig.CipherMode.CHACHA20,true,keyPair.getAccountKey(),
				Config.etchKeyResolver(keyPair));
		var expected=Maps.of(keyPair.getAccountKey(),Strings.create("stored peer data"));
		try (EtchStore created=EtchStore.create(store.toFile(),encrypted)) {
			created.setRootData(expected);
			created.flush();
		}

		Path file=temporaryDirectory.resolve("store-only.json5");
		String path=store.toAbsolutePath().toString().replace('\\','/');
		Files.writeString(file,"{peer:{store:'"+path+"'}}");
		String[] options={"--config",file.toString(),"--keystore",keyFile.toString(),
				"--storepass",new String(storePassword),"--peer-keypass",new String(keyPassword),
				"--strict-security"};
		ParsedCommand parsed=parse("start",options);
		HashMap<Keyword,Object> launchConfig=parsed.command().loadPeerConfig();
		try (AStore opened=parsed.command().openPeerStore(launchConfig)) {
			EtchStore etchStore=(EtchStore)opened;
			assertEquals(keyPair.getAccountKey(),etchStore.getEtch().getConfig().getPublicKeyHint());
			assertEquals(expected,etchStore.getRootData());
			AKeyPair selected=((PeerStart)parsed.command()).findPeerKey(opened,launchConfig);
			assertEquals(keyPair.getAccountKey(),selected.getAccountKey());
		}
	}

	@Test
	void newEncryptedStoreDefaultsHintToExplicitPeerKey() throws Exception {
		Path keyFile=temporaryDirectory.resolve("default-hint-keys.pfx");
		char[] storePassword="store-secret".toCharArray();
		char[] keyPassword="key-secret".toCharArray();
		AKeyPair keyPair=AKeyPair.generate();
		KeyStore keys=PFXTools.createStore(keyFile.toFile(),storePassword);
		PFXTools.setKeyPair(keys,keyPair,keyPassword);
		PFXTools.saveStore(keys,keyFile.toFile(),storePassword);

		Path store=storePath("default-hint-v3");
		Path file=config(store,"{version:3,cipher:'aes-256-ctr',encryptIndex:true}");
		String[] options={"--config",file.toString(),"--keystore",keyFile.toString(),
				"--storepass",new String(storePassword),"--peer-keypass",new String(keyPassword),
				"--peer-key",keyPair.getAccountKey().toHexString()};
		ParsedCommand parsed=parse("start",options);
		try (AStore opened=parsed.command().openPeerStore(parsed.command().loadPeerConfig())) {
			EtchConfig actual=((EtchStore)opened).getEtch().getConfig();
			assertEquals(keyPair.getAccountKey(),actual.getPublicKeyHint());
			assertEquals(EtchConfig.CipherMode.AES_256_CTR,actual.getCipherMode());
		}
	}

	private Path config(Path store, String etch) throws Exception {
		Path file=temporaryDirectory.resolve(store.getFileName()+".json5");
		String path=store.toAbsolutePath().toString().replace('\\','/');
		Files.writeString(file,"{peer:{store:'"+path+"',etch:"+etch+"}}");
		return file;
	}

	private Path storePath(String prefix) throws Exception {
		Path store=Files.createTempFile(prefix,".etch");
		Files.delete(store);
		store.toFile().deleteOnExit();
		return store;
	}

	private ParsedCommand parse(String command, String... options) {
		Main main=new Main();
		StringWriter error=new StringWriter();
		main.commandLine.setErr(new PrintWriter(error,true));
		String[] arguments=new String[options.length+2];
		arguments[0]="peer";
		arguments[1]=command;
		System.arraycopy(options,0,arguments,2,options.length);
		var root=main.commandLine.parseArgs(arguments);
		APeerCommand parsed=(APeerCommand)root.subcommand().subcommand().commandSpec().userObject();
		return new ParsedCommand(parsed,error);
	}

	private record ParsedCommand(APeerCommand command, StringWriter error) {}
}
