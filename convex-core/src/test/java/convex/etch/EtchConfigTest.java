package convex.etch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;

import org.junit.jupiter.api.Test;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AccountKey;
import convex.core.data.Hash;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.prim.CVMBool;
import convex.core.data.prim.CVMLong;

public class EtchConfigTest {
	@Test
	public void testDefaultsAreCompiled() {
		EtchConfig config=EtchConfig.create();
		assertEquals(EtchConstants.CURRENT_VERSION,config.getVersion());
		assertEquals(EtchFileMapperFactory.defaultMapping(EtchConstants.CURRENT_VERSION),
				config.getMappingMode());
		assertTrue(config.isBuildChains());
	}

	@Test
	public void testCompileMap() {
		AMap<AString,ACell> source=Maps.of(
				EtchConfig.VERSION,CVMLong.create(EtchConstants.VERSION_2),
				EtchConfig.MAPPING,Strings.create("mapped-byte-buffer"),
				EtchConfig.BUILD_CHAINS,CVMBool.FALSE);

		EtchConfig config=EtchConfig.fromMap(source);
		assertEquals(EtchConstants.VERSION_2,config.getVersion());
		assertEquals(EtchConfig.MappingMode.MAPPED_BYTE_BUFFER,config.getMappingMode());
		assertFalse(config.isBuildChains());
		assertNotNull(config.toString());
	}

	@Test
	public void testStrictMapValidation() {
		assertThrows(IllegalArgumentException.class,() -> EtchConfig.fromMap(
				Maps.of(Strings.create("unknown"),CVMLong.ONE)));
		assertThrows(IllegalArgumentException.class,() -> EtchConfig.fromMap(
				Maps.of(EtchConfig.VERSION,Strings.create("2"))));
		assertThrows(IllegalArgumentException.class,() -> EtchConfig.fromMap(
				Maps.of(EtchConfig.BUILD_CHAINS,CVMLong.ONE)));
		assertThrows(IllegalArgumentException.class,() -> EtchConfig.fromMap(
				Maps.of(EtchConfig.MAPPING,Strings.create("unknown"))));
		assertThrows(IllegalArgumentException.class,() -> EtchConfig.fromMap(Maps.of(
				EtchConfig.VERSION,CVMLong.create(EtchConstants.VERSION_3),
				EtchConfig.PUBLIC_KEY_HINT,Strings.create("1234"))));
		assertThrows(IllegalArgumentException.class,() -> EtchConfig.create(
				EtchConstants.VERSION_1,EtchConfig.MappingMode.MEMORY_SEGMENT,true));
	}

	@Test
	public void testV3PublicKeyHintConfiguration() {
		AccountKey hint=AccountKey.fromHex(
				"202122232425262728292a2b2c2d2e2f303132333435363738393a3b3c3d3e3f");
		EtchConfig direct=EtchConfig.create(EtchConstants.VERSION_3,
				EtchConfig.MappingMode.MAPPED_BYTE_BUFFER,true,hint);
		assertEquals(hint,direct.getPublicKeyHint());
		assertEquals(direct,EtchConfig.create(EtchConstants.VERSION_3,
				EtchConfig.MappingMode.MAPPED_BYTE_BUFFER,true).withPublicKeyHint(hint));

		AMap<AString,ACell> source=Maps.of(
				EtchConfig.VERSION,CVMLong.create(EtchConstants.VERSION_3),
				EtchConfig.MAPPING,Strings.create("mapped-byte-buffer"),
				EtchConfig.PUBLIC_KEY_HINT,Strings.create(hint.toHexString()));
		assertEquals(hint,EtchConfig.fromMap(source).getPublicKeyHint());

		assertNull(EtchConfig.create(EtchConstants.VERSION_3,
				EtchConfig.MappingMode.MAPPED_BYTE_BUFFER,true,AccountKey.ZERO)
				.getPublicKeyHint());
	}

	@Test
	public void testV3EncryptionConfiguration() {
		byte[] secret=new byte[EtchConstants.V3_MASTER_KEY_SIZE];
		secret[0]=1;
		AMap<AString,ACell> source=Maps.of(
				EtchConfig.VERSION,CVMLong.create(EtchConstants.VERSION_3),
				EtchConfig.CIPHER,Strings.create("aes-256-ctr"),
				EtchConfig.ENCRYPT_INDEX,CVMBool.TRUE);
		EtchConfig config=EtchConfig.fromMap(source,hint->secret.clone());

		assertEquals(EtchConfig.CipherMode.AES_256_CTR,config.getCipherMode());
		assertTrue(config.isIndexEncrypted());
		assertTrue(config.hasKeyFunction());
		assertTrue(config.toString().contains("keyFunction=present"));
		assertEquals(config,EtchConfig.createV3(config.getMappingMode(),true,
				EtchConfig.CipherMode.AES_256_CTR,true,null,hint->new byte[32]));
	}

	@Test
	public void testV3ChaCha20Configuration() {
		byte[] secret=new byte[EtchConstants.V3_MASTER_KEY_SIZE];
		EtchConfig config=EtchConfig.fromMap(Maps.of(
				EtchConfig.VERSION,CVMLong.create(EtchConstants.VERSION_3),
				EtchConfig.CIPHER,Strings.create("chacha20")),hint->secret.clone());
		assertEquals(EtchConfig.CipherMode.CHACHA20,config.getCipherMode());
		assertFalse(config.isIndexEncrypted());
		assertTrue(config.hasKeyFunction());
	}

	@Test
	public void testInvalidV3EncryptionConfiguration() {
		assertThrows(IllegalArgumentException.class,()->EtchConfig.createV3(
				EtchConfig.MappingMode.MAPPED_BYTE_BUFFER,true,
				EtchConfig.CipherMode.AES_256_CTR,false,null,null));
		assertThrows(IllegalArgumentException.class,()->EtchConfig.createV3(
				EtchConfig.MappingMode.MAPPED_BYTE_BUFFER,true,
				EtchConfig.CipherMode.NONE,true,null,null));
		assertThrows(IllegalArgumentException.class,()->EtchConfig.fromMap(Maps.of(
				EtchConfig.VERSION,CVMLong.create(EtchConstants.VERSION_2),
				EtchConfig.CIPHER,Strings.create("aes-256-ctr")),hint->new byte[32]));
	}

	@Test
	public void testPublicKeyHintRejectedForLegacyVersions() {
		AccountKey hint=AccountKey.dummy("1234");
		assertThrows(IllegalArgumentException.class,()->EtchConfig.create(
				EtchConstants.VERSION_1,EtchConfig.MappingMode.MAPPED_BYTE_BUFFER,true,hint));
		assertThrows(IllegalArgumentException.class,()->EtchConfig.create(
				EtchConstants.VERSION_2,EtchConfig.MappingMode.MAPPED_BYTE_BUFFER,true,hint));
		assertThrows(IllegalArgumentException.class,
				()->EtchConfig.create(EtchConstants.VERSION_2).withPublicKeyHint(hint));
		assertThrows(IllegalArgumentException.class,()->EtchConfig.fromMap(Maps.of(
				EtchConfig.VERSION,CVMLong.create(EtchConstants.VERSION_2),
				EtchConfig.PUBLIC_KEY_HINT,Strings.create(hint.toHexString()))));
	}

	@Test
	public void testCompiledBuildChainsOptionIsUsed() throws IOException {
		EtchConfig config=EtchConfig.create(EtchConstants.VERSION_2,
				EtchConfig.MappingMode.MAPPED_BYTE_BUFFER,false);
		Etch etch=Etch.createTempEtch(config);
		EtchStore store=new EtchStore(etch);
		Hash first=Hash.fromHex("0000000000000001000000000000000000000000000000000000000000000000");
		Hash second=Hash.fromHex("0000000000000002000000000000000000000000000000000000000000000000");
		etch.write(first,first.getRef());
		etch.write(second,second.getRef());

		long rootSlot=etch.readSlot(etch.getIndexStart(),0);
		assertEquals(EtchConstants.POINTER_INDEX,etch.extractType(rootSlot));
		store.close();
	}

	@Test
	public void testConfiguredEtchAndLegacyInference() throws IOException {
		File file=File.createTempFile("etch-config", ".etch");
		file.deleteOnExit();
		EtchConfig configured=EtchConfig.create(EtchConstants.VERSION_1,
				EtchConfig.MappingMode.MAPPED_BYTE_BUFFER,false);

		Etch etch=Etch.create(file,configured);
		assertEquals(configured,etch.getConfig());
		assertEquals("MappedByteBuffer",etch.getMappingImplementation());
		etch.close();

		Etch reopened=Etch.create(file);
		assertEquals(EtchConstants.VERSION_1,reopened.getConfig().getVersion());
		assertEquals(EtchConfig.MappingMode.MAPPED_BYTE_BUFFER,reopened.getConfig().getMappingMode());
		assertTrue(reopened.getConfig().isBuildChains(),"Non-persisted options use defaults on legacy reopen");
		reopened.close();
	}

	@Test
	public void testVersionMismatchReleasesFile() throws IOException {
		File file=File.createTempFile("etch-config-mismatch", ".etch");
		file.deleteOnExit();
		Etch configured=Etch.create(file,EtchConfig.create(EtchConstants.VERSION_1));
		configured.close();

		assertThrows(IOException.class,
				() -> Etch.create(file,EtchConfig.create(EtchConstants.VERSION_2)));

		// A failed configured open must release its channel and exclusive lock.
		Etch reopened=Etch.create(file);
		assertEquals(EtchConstants.VERSION_1,reopened.getVersion());
		reopened.close();
	}
}
