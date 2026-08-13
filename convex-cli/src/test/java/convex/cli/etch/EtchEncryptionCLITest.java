package convex.cli.etch;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.Arrays;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import convex.cli.CLTester;
import convex.cli.ExitCodes;
import convex.cli.Helpers;
import convex.core.crypto.AKeyPair;
import convex.core.crypto.PFXTools;
import convex.core.data.ACell;
import convex.core.data.AString;
import convex.core.data.Strings;
import convex.core.lang.RT;
import convex.etch.EtchConfig;
import convex.etch.EtchConfig.CipherMode;
import convex.etch.EtchConstants;
import convex.etch.EtchStore;
import convex.peer.Config;

class EtchEncryptionCLITest {

	private static final byte[] SOURCE_KEY=
			"0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8);
	private static final byte[] DESTINATION_KEY=
			"fedcba9876543210fedcba9876543210".getBytes(StandardCharsets.UTF_8);

	@TempDir
	Path temporaryDirectory;

	@Test
	void encryptedCommandsAndPreservingOutputs() throws Exception {
		Path source=etchFile("encrypted-cli-source");
		Path sourceKey=keyFile("source.key",SOURCE_KEY);
		AString root=Strings.create("Encrypted Etch CLI root "+"0123456789abcdef".repeat(16));
		EtchConfig sourceConfig=config(CipherMode.AES_256_CTR,true,SOURCE_KEY);
		createStore(source,sourceConfig,root);

		byte[] beforeValidate=Files.readAllBytes(source);
		CLTester tester=runSource("validate",source,sourceKey);
		tester.assertExitCode(ExitCodes.SUCCESS);
		assertArrayEquals(beforeValidate,Files.readAllBytes(source),
				"offline validation must not alter its source");

		tester=runSource("info",source,sourceKey);
		tester.assertExitCode(ExitCodes.SUCCESS);
		assertTrue(tester.getOutput().contains("Etch version:"));

		tester=CLTester.run("etch","read","--etch",source.toString(),
				"--etch-key-file",sourceKey.toString(),root.getHash().toHexString());
		tester.assertExitCode(ExitCodes.SUCCESS);
		assertEquals(RT.toString(root),tester.getOutput().trim());

		tester=runSource("dump",source,sourceKey);
		tester.assertExitCode(ExitCodes.SUCCESS);
		assertTrue(tester.getOutput().contains(root.getHash().toHexString()));

		Path collected=etchFile("encrypted-cli-collected");
		tester=CLTester.run("etch","gc","--etch",source.toString(),
				"--etch-key-file",sourceKey.toString(),"--output",collected.toString());
		tester.assertExitCode(ExitCodes.SUCCESS);
		assertPreservedEncryptedStore(source,collected,sourceConfig,root,SOURCE_KEY);

		byte[] beforeRepair=Files.readAllBytes(source);
		Path repaired=etchFile("encrypted-cli-repaired");
		tester=CLTester.run("etch","repair","--etch",source.toString(),
				"--etch-key-file",sourceKey.toString(),"--into",repaired.toString());
		tester.assertExitCode(ExitCodes.SUCCESS);
		assertArrayEquals(beforeRepair,Files.readAllBytes(source),
				"repair must be byte-for-byte read-only on its source");
		assertPreservedEncryptedStore(source,repaired,sourceConfig,root,SOURCE_KEY);
	}

	@Test
	void migrationSupportsEveryCipherTransitionAndSeparateKeys() throws Exception {
		Path sourceKey=keyFile("transition-source.key",SOURCE_KEY);
		Path destinationKey=keyFile("transition-destination.key",DESTINATION_KEY);
		for (CipherMode sourceCipher:CipherMode.values()) {
			Path source=etchFile("cli-from-"+sourceCipher.configName());
			AString root=Strings.create("CLI transition from "+sourceCipher.configName()
					+" "+"abcdef0123456789".repeat(10));
			createStore(source,config(sourceCipher,sourceCipher!=CipherMode.NONE,SOURCE_KEY),root);

			for (CipherMode destinationCipher:CipherMode.values()) {
				Path destination=etchFile("cli-from-"+sourceCipher.configName()
						+"-to-"+destinationCipher.configName());
				ArrayList<String> args=new ArrayList<>(Arrays.asList("etch","migrate",
						"--etch",source.toString(),"--etch-key-file",sourceKey.toString(),
						"--into",destination.toString(),"--set-root","--into-version","3",
						"--into-cipher",destinationCipher.configName()));
				if (destinationCipher==CipherMode.NONE) {
					args.add("--no-into-encrypt-index");
				} else {
					args.add("--into-encrypt-index");
					args.add("--into-key-file");
					args.add(destinationKey.toString());
				}

				CLTester tester=CLTester.run(args.toArray(String[]::new));
				tester.assertExitCode(ExitCodes.SUCCESS);
				EtchConfig open=EtchConfig.create().withKeyFunction(
						hint->DESTINATION_KEY.clone());
				try (EtchStore migrated=EtchStore.create(destination.toFile(),open)) {
					assertEquals(root,migrated.getRootData());
					assertEquals(destinationCipher,
							migrated.getEtch().getConfig().getCipherMode());
					assertEquals(destinationCipher!=CipherMode.NONE,
							migrated.getEtch().getConfig().isIndexEncrypted());
				}
			}
		}
	}

	@Test
	void hintedKeystoreAndStdinResolutionDoNotLeakSecrets() throws Exception {
		char[] storePassword="store-secret".toCharArray();
		char[] keyPassword="entry-secret".toCharArray();
		AKeyPair keyPair=AKeyPair.generate();
		Path keyStoreFile=temporaryDirectory.resolve("etch-keys.pfx");
		KeyStore keyStore=PFXTools.createStore(keyStoreFile.toFile(),storePassword);
		PFXTools.setKeyPair(keyStore,keyPair,keyPassword);
		PFXTools.saveStore(keyStore,keyStoreFile.toFile(),storePassword);

		Path hinted=etchFile("hinted-cli-etch");
		AString hintedRoot=Strings.create("Header-hinted keystore resolution");
		EtchConfig hintedConfig=EtchConfig.createV3(EtchConfig.MappingMode.MAPPED_BYTE_BUFFER,
				true,CipherMode.CHACHA20,true,keyPair.getAccountKey(),
				Config.etchKeyResolver(keyPair));
		createStore(hinted,hintedConfig,hintedRoot);

		CLTester tester=CLTester.run("etch","info","--etch",hinted.toString(),
				"--keystore",keyStoreFile.toString(),"--storepass",new String(storePassword),
				"--etch-keypass",new String(keyPassword));
		tester.assertExitCode(ExitCodes.SUCCESS);

		Path hintedDestination=etchFile("hinted-cli-destination");
		tester=CLTester.run("etch","migrate","--etch",hinted.toString(),
				"--keystore",keyStoreFile.toString(),"--storepass",new String(storePassword),
				"--etch-keypass",new String(keyPassword),"--into",hintedDestination.toString(),
				"--set-root","--into-version","3","--into-cipher","aes-256-ctr",
				"--into-encrypt-index","--into-key",
				keyPair.getAccountKey().toHexString().substring(0,16),
				"--into-keypass",new String(keyPassword),"--into-public-key-hint",
				keyPair.getAccountKey().toHexString());
		tester.assertExitCode(ExitCodes.SUCCESS);
		try (EtchStore migrated=EtchStore.create(hintedDestination.toFile(),
				EtchConfig.create().withKeyFunction(Config.etchKeyResolver(keyPair)))) {
			assertEquals(keyPair.getAccountKey(),
					migrated.getEtch().getConfig().getPublicKeyHint());
			assertEquals(hintedRoot,migrated.getRootData());
		}

		Path unhinted=etchFile("stdin-cli-etch");
		createStore(unhinted,config(CipherMode.AES_256_CTR,false,SOURCE_KEY),hintedRoot);
		tester=CLTester.runWithInput(new String(SOURCE_KEY,StandardCharsets.UTF_8),
				"etch","info","--etch",unhinted.toString(),"--etch-key-file","-");
		tester.assertExitCode(ExitCodes.SUCCESS);

		Path wrongKey=keyFile("wrong.key",DESTINATION_KEY);
		tester=CLTester.run("etch","info","--etch",unhinted.toString(),
				"--etch-key-file",wrongKey.toString());
		assertEquals(ExitCodes.ERROR,tester.getResult());
		String exposed=new String(DESTINATION_KEY,StandardCharsets.UTF_8);
		assertFalse(tester.getOutput().contains(exposed));
		assertFalse(tester.getError().contains(exposed));
	}

	private CLTester runSource(String command, Path source, Path keyFile) {
		return CLTester.run("etch",command,"--etch",source.toString(),
				"--etch-key-file",keyFile.toString());
	}

	private Path keyFile(String name, byte[] key) throws Exception {
		Path path=temporaryDirectory.resolve(name);
		Files.write(path,key);
		return path;
	}

	private static Path etchFile(String prefix) throws Exception {
		return Helpers.createTempFile(prefix,".etch").toPath();
	}

	private static EtchConfig config(CipherMode cipher, boolean encryptedIndex,
			byte[] key) {
		return EtchConfig.createV3(EtchConfig.MappingMode.MAPPED_BYTE_BUFFER,true,
				cipher,encryptedIndex,null,hint->key.clone());
	}

	private static void createStore(Path file, EtchConfig config, ACell root)
			throws Exception {
		try (EtchStore store=EtchStore.create(file.toFile(),config)) {
			store.setRootData(root);
			store.flush();
		}
	}

	private static void assertPreservedEncryptedStore(Path source, Path destination,
			EtchConfig expected, ACell root, byte[] key) throws Exception {
		EtchConfig open=EtchConfig.create().withKeyFunction(hint->key.clone());
		try (EtchStore store=EtchStore.create(destination.toFile(),open)) {
			EtchConfig actual=store.getEtch().getConfig();
			assertEquals(expected.getVersion(),actual.getVersion());
			assertEquals(expected.getCipherMode(),actual.getCipherMode());
			assertEquals(expected.isIndexEncrypted(),actual.isIndexEncrypted());
			assertEquals(root,store.getRootData());
		}
		byte[] sourceHeader=Files.readAllBytes(source);
		byte[] destinationHeader=Files.readAllBytes(destination);
		int offset=EtchConstants.V3_FILE_SALT_OFFSET;
		int end=offset+EtchConstants.V3_FILE_SALT_SIZE;
		assertNotEquals(Arrays.toString(Arrays.copyOfRange(sourceHeader,offset,end)),
				Arrays.toString(Arrays.copyOfRange(destinationHeader,offset,end)),
				"fresh destination must have an independent v3 salt");
	}
}
