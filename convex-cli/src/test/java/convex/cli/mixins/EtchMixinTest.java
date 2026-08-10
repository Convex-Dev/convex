package convex.cli.mixins;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.File;

import org.junit.jupiter.api.Test;

import convex.cli.CLIError;
import convex.etch.EtchConfig;
import convex.etch.EtchConfig.CipherMode;
import convex.etch.EtchConfig.MappingMode;
import convex.etch.EtchStore;

public class EtchMixinTest {

	@Test
	public void testConfiguredEncryptedOpen() throws Exception {
		File file=File.createTempFile("configured-cli", ".etch");
		file.deleteOnExit();
		EtchConfig config=config((byte)0x20);
		try (EtchStore created=EtchStore.create(file,config)) {
			created.flush();
		}

		EtchMixin mixin=new EtchMixin();
		try (EtchStore opened=mixin.getEtchStore(file.getPath(),config)) {
			assertEquals(config,opened.getEtch().getConfig());
		}
	}

	@Test
	public void testWrongConfigurationFailsBeforeOpen() throws Exception {
		File file=File.createTempFile("wrong-key-cli", ".etch");
		file.deleteOnExit();
		EtchConfig correct=config((byte)0x30);
		try (EtchStore created=EtchStore.create(file,correct)) {
			created.flush();
		}
		long length=file.length();
		long modified=file.lastModified();

		EtchMixin mixin=new EtchMixin();
		assertThrows(CLIError.class,()->mixin.getEtchStore(file.getPath(),config((byte)0x31)));
		assertEquals(length,file.length());
		assertEquals(modified,file.lastModified());
	}

	private static EtchConfig config(byte first) {
		byte[] secret=new byte[32];
		secret[0]=first;
		return EtchConfig.createV3(MappingMode.MAPPED_BYTE_BUFFER,true,
				CipherMode.AES_256_CTR,true,null,hint->secret.clone());
	}
}
