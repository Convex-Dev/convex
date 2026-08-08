package convex.etch;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import org.junit.jupiter.api.Test;

import convex.core.data.AString;
import convex.core.data.AccountKey;
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
		EtchV3Header flushed=readHeader(file,null);
		assertTrue(flushed.isCleanClosed());
		long flushGeneration=flushed.generation();
		store.close();
		assertEquals(flushGeneration,readHeader(file,null).generation(),
				"closing an already clean checkpoint must not rewrite its header");

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

	@Test
	public void testKeyFunctionReturnsBorrowedKeyNotRetainedOrModified() throws Exception {
		File file=tempFile("etch-v3-key-ownership");
		byte[] borrowed=SECRET.clone();
		AccountKey[] seenHint=new AccountKey[1];
		EtchConfig config=EtchConfig.createV3(EtchConfig.MappingMode.MAPPED_BYTE_BUFFER,
				true,EtchConfig.CipherMode.AES_256_CTR,false,null,hint->{
					seenHint[0]=hint;
					return borrowed;
				});

		Etch etch=Etch.create(file,config);
		assertNull(seenHint[0]);
		assertArrayEquals(SECRET,borrowed);
		java.util.Arrays.fill(borrowed,(byte)0);
		AString stored=value("borrowed-key");
		EtchStore store=new EtchStore(etch);
		store.setRootData(stored);
		store.close();

		EtchConfig reopenConfig=EtchConfig.create(EtchConstants.VERSION_3)
				.withKeyFunction(hint->SECRET.clone());
		EtchStore reopened=new EtchStore(Etch.create(file,reopenConfig));
		try {
			assertEquals(stored,reopened.getRootData());
		} finally {
			reopened.close();
		}
	}

	@Test
	public void testKeyFunctionReceivesPublicKeyHintFromFile() throws Exception {
		File file=tempFile("etch-v3-key-hint");
		AccountKey expectedHint=AccountKey.wrap(sequence(0x40,AccountKey.LENGTH));
		AccountKey[] createHint=new AccountKey[1];
		EtchConfig config=EtchConfig.createV3(EtchConfig.MappingMode.MAPPED_BYTE_BUFFER,
				true,EtchConfig.CipherMode.AES_256_CTR,false,expectedHint,hint->{
					createHint[0]=hint;
					return SECRET.clone();
				});

		Etch.create(file,config).close();
		assertEquals(expectedHint,createHint[0]);

		AccountKey[] openHint=new AccountKey[1];
		EtchConfig openConfig=EtchConfig.create(EtchConstants.VERSION_3).withKeyFunction(hint->{
			openHint[0]=hint;
			return SECRET.clone();
		});
		Etch.create(file,openConfig).close();
		assertEquals(expectedHint,openHint[0]);
	}

	@Test
	public void testSecondV3CopyWinsOverDamagedLegacyLookingProbe() throws Exception {
		File file=tempFile("etch-v3-second-probe");
		EtchConfig config=EtchConfig.createV3(EtchConfig.MappingMode.MAPPED_BYTE_BUFFER,
				true,EtchConfig.CipherMode.AES_256_CTR,false,null,hint->SECRET.clone());
		Etch etch=Etch.create(file,config);
		etch.flush();
		etch.close();

		try (RandomAccessFile data=new RandomAccessFile(file,"rw")) {
			// Preserve a valid clean v3 copy in B, then make the leading copy look
			// legacy. The format probe must still discover and select B.
			byte[] cleanCopy=new byte[EtchConstants.V3_HEADER_COPY_SIZE];
			data.seek(EtchConstants.V3_HEADER_A_OFFSET);
			data.readFully(cleanCopy);
			data.seek(EtchConstants.V3_HEADER_B_OFFSET);
			data.write(cleanCopy);
			data.seek(Short.BYTES);
			data.writeShort(EtchConstants.VERSION_1);
		}

		EtchConfig openConfig=EtchConfig.create(EtchConstants.VERSION_3)
				.withKeyFunction(hint->SECRET.clone());
		Etch reopened=Etch.create(file,openConfig);
		try {
			assertEquals(EtchConstants.VERSION_3,reopened.getVersion());
		} finally {
			reopened.close();
		}
	}

	@Test
	public void testKeyFunctionFailurePropagatesWithoutFileChanges() throws Exception {
		File file=tempFile("etch-v3-key-failure");
		RuntimeException expected=new RuntimeException("key lookup failed");
		EtchConfig config=EtchConfig.createV3(EtchConfig.MappingMode.MAPPED_BYTE_BUFFER,
				true,EtchConfig.CipherMode.AES_256_CTR,false,null,hint->{throw expected;});

		RuntimeException actual=assertThrows(RuntimeException.class,()->Etch.create(file,config));
		assertSame(expected,actual);
		assertEquals(0L,file.length());
	}

	@Test
	public void testKeyFunctionFailureDoesNotCreateTarget() throws Exception {
		File file=tempFile("etch-v3-key-no-target");
		assertTrue(file.delete());
		RuntimeException expected=new RuntimeException("key lookup failed");
		EtchConfig config=EtchConfig.createV3(EtchConfig.MappingMode.MAPPED_BYTE_BUFFER,
				true,EtchConfig.CipherMode.AES_256_CTR,false,null,hint->{throw expected;});

		RuntimeException actual=assertThrows(RuntimeException.class,()->Etch.create(file,config));
		assertSame(expected,actual);
		assertFalse(file.exists());
	}

	@Test
	public void testKeyFunctionFailureDoesNotModifyExistingFile() throws Exception {
		File file=tempFile("etch-v3-key-existing-failure");
		EtchConfig createConfig=EtchConfig.createV3(EtchConfig.MappingMode.MAPPED_BYTE_BUFFER,
				true,EtchConfig.CipherMode.AES_256_CTR,false,null,hint->SECRET.clone());
		Etch.create(file,createConfig).close();
		byte[] before=Files.readAllBytes(file.toPath());
		RuntimeException expected=new RuntimeException("key lookup failed");
		EtchConfig openConfig=EtchConfig.create(EtchConstants.VERSION_3)
				.withKeyFunction(hint->{throw expected;});

		RuntimeException actual=assertThrows(RuntimeException.class,()->Etch.create(file,openConfig));
		assertSame(expected,actual);
		assertArrayEquals(before,Files.readAllBytes(file.toPath()));
	}

	@Test
	public void testInvalidReturnedKeyIsNotModifiedWithoutFileChanges() throws Exception {
		File file=tempFile("etch-v3-invalid-key");
		byte[] invalid=new byte[31];
		java.util.Arrays.fill(invalid,(byte)0x5a);
		EtchConfig config=EtchConfig.createV3(EtchConfig.MappingMode.MAPPED_BYTE_BUFFER,
				true,EtchConfig.CipherMode.AES_256_CTR,false,null,hint->invalid);

		assertThrows(IOException.class,()->Etch.create(file,config));
		byte[] expected=new byte[invalid.length];
		java.util.Arrays.fill(expected,(byte)0x5a);
		assertArrayEquals(expected,invalid);
		assertEquals(0L,file.length());
	}

	private static void assertEncryptedRoundTrip(EtchConfig.CipherMode cipherMode,
			boolean encryptedIndex) throws Exception {
		String cipherName=cipherMode.configName();
		File file=tempFile("etch-v3-"+cipherName+(encryptedIndex?"-index":""));
		AString value=value(cipherName+(encryptedIndex?"-index":"-data"));
		EtchConfig config=EtchConfig.createV3(EtchConfig.MappingMode.MAPPED_BYTE_BUFFER,
				true,cipherMode,encryptedIndex,null,hint->SECRET.clone());

		EtchStore store=new EtchStore(Etch.create(file,config));
		store.setRootData(value);
		store.flush();
		store.close();

		byte[] wrong=SECRET.clone();
		wrong[0]^=1;
		EtchConfig wrongConfig=EtchConfig.createV3(config.getMappingMode(),true,
				cipherMode,encryptedIndex,null,hint->wrong.clone());
		assertThrows(IOException.class,()->Etch.create(file,wrongConfig));
		assertThrows(IOException.class,()->Etch.create(file));

		EtchConfig openConfig=EtchConfig.create(EtchConstants.VERSION_3)
				.withKeyFunction(hint->SECRET.clone());
		EtchStore reopened=new EtchStore(Etch.create(file,openConfig));
		try {
			assertEquals(value,reopened.getRootData());
			assertEquals(cipherMode,reopened.getEtch().getConfig().getCipherMode());
			assertEquals(encryptedIndex,reopened.getEtch().getConfig().isIndexEncrypted());
		} finally {
			reopened.close();
		}
	}

	private static EtchV3Header readHeader(File file, byte[] secret) throws Exception {
		try (RandomAccessFile data=new RandomAccessFile(file,"r");
				AFileMapper mapper=new MBBFileMapper(data.getChannel(),true)) {
			return (EtchV3Header)AEtchHeader.open(mapper,file.getName(),secret);
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
