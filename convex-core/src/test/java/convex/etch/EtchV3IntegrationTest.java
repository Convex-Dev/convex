package convex.etch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import org.junit.jupiter.api.Test;

import convex.core.data.AString;
import convex.core.data.Strings;

public class EtchV3IntegrationTest {
	private static final byte[] SECRET=sequence(0x20,32);

	@Test
	public void testPlaintextCreateCloseAndInferredReopen() throws Exception {
		File file=tempFile("etch-v3-plain");
		AString value=value("plaintext");
		EtchConfig config=EtchConfig.create(EtchConstants.VERSION_3);

		EtchStore store=new EtchStore(Etch.create(file,config));
		store.setRootData(value);
		store.flush();
		store.close();

		EtchStore reopened=new EtchStore(Etch.create(file));
		try {
			assertEquals(value,reopened.getRootData());
			assertEquals(EtchConfig.CipherMode.NONE,reopened.getEtch().getConfig().getCipherMode());
			assertEquals(EtchConstants.VERSION_3,reopened.getEtch().getVersion());
		} finally {
			reopened.close();
		}
	}

	@Test
	public void testAESCreateWrongKeyAndReopen() throws Exception {
		assertEncryptedRoundTrip(EtchConfig.CipherMode.AES_256_CTR,false);
	}

	@Test
	public void testAESEncryptedIndexRoundTrip() throws Exception {
		assertEncryptedRoundTrip(EtchConfig.CipherMode.AES_256_CTR,true);
	}

	@Test
	public void testChaCha20CreateWrongKeyAndReopen() throws Exception {
		assertEncryptedRoundTrip(EtchConfig.CipherMode.CHACHA20,false);
	}

	@Test
	public void testChaCha20EncryptedIndexRoundTrip() throws Exception {
		assertEncryptedRoundTrip(EtchConfig.CipherMode.CHACHA20,true);
	}

	@Test
	public void testCleanReadOnlyReopenDoesNotRewriteHeader() throws Exception {
		File file=tempFile("etch-v3-clean-generation");
		EtchConfig config=EtchConfig.create(EtchConstants.VERSION_3);
		Etch first=Etch.create(file,config);
		first.close();
		long generation=readHeader(file,null).generation();

		Etch reopened=Etch.create(file,config);
		reopened.close();
		assertEquals(generation,readHeader(file,null).generation());

		Etch writer=Etch.create(file,config);
		writer.write(value("mutation").getHash(),value("mutation").getRef());
		writer.close();
		EtchV3Header mutated=readHeader(file,null);
		assertEquals(generation+2L,mutated.generation());
		assertTrue(mutated.isCleanClosed());
	}

	private static void assertEncryptedRoundTrip(EtchConfig.CipherMode cipherMode,
			boolean encryptedIndex) throws Exception {
		String cipherName=cipherMode.configName();
		File file=tempFile("etch-v3-"+cipherName+(encryptedIndex?"-index":""));
		AString value=value(cipherName+(encryptedIndex?"-index":"-data"));
		EtchConfig config=EtchConfig.createV3(EtchConfig.MappingMode.MAPPED_BYTE_BUFFER,
				true,cipherMode,encryptedIndex,null,SECRET);

		EtchStore store=new EtchStore(Etch.create(file,config));
		store.setRootData(value);
		store.flush();
		store.close();

		byte[] wrong=SECRET.clone();
		wrong[0]^=1;
		EtchConfig wrongConfig=EtchConfig.createV3(config.getMappingMode(),true,
				cipherMode,encryptedIndex,null,wrong);
		assertThrows(IOException.class,()->Etch.create(file,wrongConfig));
		assertThrows(IOException.class,()->Etch.create(file));

		EtchStore reopened=new EtchStore(Etch.create(file,config));
		try {
			assertEquals(value,reopened.getRootData());
			assertEquals(cipherMode,reopened.getEtch().getConfig().getCipherMode());
			assertEquals(encryptedIndex,reopened.getEtch().getConfig().isIndexEncrypted());
		} finally {
			reopened.close();
		}
	}

	private static EtchV3Header readHeader(File file, byte[] secret) throws Exception {
		try (RandomAccessFile data=new RandomAccessFile(file,"r")) {
			return (EtchV3Header)EtchHeader.open(data,file.getName(),secret);
		}
	}

	private static File tempFile(String prefix) throws IOException {
		File file=File.createTempFile(prefix,".etch");
		file.deleteOnExit();
		return file;
	}

	private static AString value(String suffix) {
		return Strings.create("Etch v3 integration "+suffix+" "+"0123456789abcdef".repeat(8));
	}

	private static byte[] sequence(int start, int length) {
		byte[] result=new byte[length];
		for (int i=0;i<length;i++) result[i]=(byte)(start+i);
		return result;
	}
}
