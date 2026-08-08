package convex.etch;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import convex.core.data.ACell;
import convex.core.data.AVector;
import convex.core.data.AccountKey;

/** Tests configuration retention across EtchStore and GC file lifecycles. */
public class EtchConfiguredLifecycleTest {

	private static final byte[] SECRET={
			0x00,0x01,0x02,0x03,0x04,0x05,0x06,0x07,
			0x08,0x09,0x0a,0x0b,0x0c,0x0d,0x0e,0x0f,
			0x10,0x11,0x12,0x13,0x14,0x15,0x16,0x17,
			0x18,0x19,0x1a,0x1b,0x1c,0x1d,0x1e,0x1f
	};

	private static final byte[] WRONG_SECRET={
			0x20,0x21,0x22,0x23,0x24,0x25,0x26,0x27,
			0x28,0x29,0x2a,0x2b,0x2c,0x2d,0x2e,0x2f,
			0x30,0x31,0x32,0x33,0x34,0x35,0x36,0x37,
			0x38,0x39,0x3a,0x3b,0x3c,0x3d,0x3e,0x3f
	};

	private static final AccountKey PUBLIC_KEY_HINT=AccountKey.wrap(WRONG_SECRET);

	@ParameterizedTest(name="{0}")
	@MethodSource("matrixCases")
	@Execution(ExecutionMode.CONCURRENT)
	public void testConfiguredCompletedGCRecoveryMatrix(MatrixCase matrixCase)
			throws IOException {
		int seed=1000;
		File base=File.createTempFile("etch-config-gc-"+matrixCase.name(),".etch");
		base.deleteOnExit();
		AVector<ACell> expected=EtchGCLifecycleTest.tree(seed);
		EtchStore old=EtchStore.create(base,matrixCase.config());
		EtchStore successor=null;
		try {
			old.setRootData(EtchGCLifecycleTest.tree(seed-10));
			old.startGC();
			old.setRootData(expected);
			old.transferGC();
			successor=old.completeGC();
			assertEquals(matrixCase.config(),successor.getEtch().getConfig());
		} finally {
			if (successor!=null) successor.close();
			old.close();
		}

		try (EtchStore reopened=EtchStore.create(base,matrixCase.config())) {
			assertEquals(expected.getHash(),reopened.getRootHash(),matrixCase.name());
			assertEquals(expected,reopened.getRootData(),matrixCase.name());
			assertEquals(matrixCase.config(),reopened.getEtch().getConfig(),matrixCase.name());
		}
		markRelatedForDeletion(base);
	}

	@Test
	public void testConfiguredAbandonedEncryptedGCRollback() throws IOException {
		EtchConfig config=encryptedConfig(EtchConfig.CipherMode.AES_256_CTR,true,SECRET);
		File base=File.createTempFile("etch-config-abandoned",".etch");
		base.deleteOnExit();
		AVector<ACell> expected=EtchGCLifecycleTest.tree(2100);
		File target;

		try (EtchStore initial=EtchStore.create(base,config)) {
			initial.setRootData(EtchGCLifecycleTest.tree(2000));
		}
		try (EtchStore collecting=EtchStore.create(base,config)) {
			collecting.startGC();
			target=collecting.getTargetEtch().getFile();
			target.deleteOnExit();
			collecting.setRootData(expected);
		}

		assertFalse(Arrays.equals(readV3Salt(base,config),readV3Salt(target,config)),
				"Each GC target must have an independent v3 file salt");
		try (EtchStore recovered=EtchStore.create(base,config)) {
			assertEquals(expected.getHash(),recovered.getRootHash());
			assertEquals(expected,recovered.getRootData());
			assertEquals(config,recovered.getEtch().getConfig());
		}
		markRelatedForDeletion(base);
	}

	@Test
	public void testWrongKeyDoesNotMutateCompletedGCState() throws IOException {
		EtchConfig config=encryptedConfig(EtchConfig.CipherMode.CHACHA20,true,SECRET);
		EtchConfig wrong=encryptedConfig(EtchConfig.CipherMode.CHACHA20,true,WRONG_SECRET);
		File base=File.createTempFile("etch-config-wrong-key",".etch");
		base.deleteOnExit();
		AVector<ACell> expected=EtchGCLifecycleTest.tree(3000);
		EtchStore old=EtchStore.create(base,config);
		EtchStore successor=null;
		try {
			old.setRootData(expected);
			old.startGC();
			old.transferGC();
			successor=old.completeGC();
		} finally {
			if (successor!=null) successor.close();
			old.close();
		}

		Map<String,byte[]> before=snapshotRelated(base);
		assertThrows(IOException.class,()->EtchStore.create(base,wrong));
		assertSnapshotsEqual(before,snapshotRelated(base));

		try (EtchStore recovered=EtchStore.create(base,config)) {
			assertEquals(expected.getHash(),recovered.getRootHash());
		}
		markRelatedForDeletion(base);
	}

	@Test
	public void testDirtyV3DoesNotMutateCompletedGCState() throws Exception {
		EtchConfig config=encryptedConfig(EtchConfig.CipherMode.AES_256_CTR,true,SECRET);
		File base=File.createTempFile("etch-config-dirty",".etch");
		base.deleteOnExit();
		EtchStore old=EtchStore.create(base,config);
		EtchStore successor=null;
		File current;
		try {
			old.setRootData(EtchGCLifecycleTest.tree(4000));
			old.startGC();
			old.transferGC();
			successor=old.completeGC();
			current=successor.getFile();
		} finally {
			if (successor!=null) successor.close();
			old.close();
		}
		markV3Open(current,config);

		Map<String,byte[]> before=snapshotRelated(base);
		assertThrows(IOException.class,()->EtchStore.create(base,config));
		assertSnapshotsEqual(before,snapshotRelated(base));
		markRelatedForDeletion(base);
	}

	@Test
	public void testConfiguredTemporaryStoreOverloads() throws IOException {
		EtchConfig config=encryptedConfig(EtchConfig.CipherMode.AES_256_CTR,false,SECRET);
		try (EtchStore generated=EtchStore.createTemp(config);
				EtchStore prefixed=EtchStore.createTemp("etch-config-temp",config)) {
			assertEquals(config,generated.getEtch().getConfig());
			assertEquals(config,prefixed.getEtch().getConfig());
			generated.getFile().deleteOnExit();
			prefixed.getFile().deleteOnExit();
		}
	}

	private static List<MatrixCase> matrixCases() {
		EtchConfig.MappingMode mapping=EtchConfig.create(EtchConstants.VERSION_3).getMappingMode();
		return List.of(
				new MatrixCase("v1",EtchConfig.create(EtchConstants.VERSION_1)),
				new MatrixCase("v2",EtchConfig.create(EtchConstants.VERSION_2)),
				new MatrixCase("v3-plain",EtchConfig.create(EtchConstants.VERSION_3)),
				new MatrixCase("v3-aes-data",EtchConfig.createV3(mapping,
						EtchConstants.DEFAULT_BUILD_CHAINS,EtchConfig.CipherMode.AES_256_CTR,
						false,PUBLIC_KEY_HINT,hint->SECRET.clone())),
				new MatrixCase("v3-aes-index",EtchConfig.createV3(mapping,
						EtchConstants.DEFAULT_BUILD_CHAINS,EtchConfig.CipherMode.AES_256_CTR,
						true,PUBLIC_KEY_HINT,hint->SECRET.clone())),
				new MatrixCase("v3-chacha-data",EtchConfig.createV3(mapping,
						EtchConstants.DEFAULT_BUILD_CHAINS,EtchConfig.CipherMode.CHACHA20,
						false,PUBLIC_KEY_HINT,hint->SECRET.clone())),
				new MatrixCase("v3-chacha-index",EtchConfig.createV3(mapping,
						EtchConstants.DEFAULT_BUILD_CHAINS,EtchConfig.CipherMode.CHACHA20,
						true,PUBLIC_KEY_HINT,hint->SECRET.clone())));
	}

	private static EtchConfig encryptedConfig(EtchConfig.CipherMode cipher,
			boolean encryptedIndex, byte[] secret) {
		EtchConfig.MappingMode mapping=EtchConfig.create(EtchConstants.VERSION_3).getMappingMode();
		return EtchConfig.createV3(mapping,EtchConstants.DEFAULT_BUILD_CHAINS,cipher,
				encryptedIndex,PUBLIC_KEY_HINT,hint->secret.clone());
	}

	private static byte[] readV3Salt(File file, EtchConfig config) throws IOException {
		byte[] secret=(config.getCipherMode()==EtchConfig.CipherMode.NONE)?null
				:config.resolveKey(config.getPublicKeyHint());
		try (RandomAccessFile data=new RandomAccessFile(file,"r")) {
			AEtchHeader header=AEtchHeader.open(data,file.getName(),secret);
			return ((EtchV3Header)header).fileSalt();
		} finally {
			Arrays.fill(secret,(byte)0);
		}
	}

	private static void markV3Open(File file, EtchConfig config) throws IOException {
		byte[] secret=(config.getCipherMode()==EtchConfig.CipherMode.NONE)?null
				:config.resolveKey(config.getPublicKeyHint());
		try (RandomAccessFile data=new RandomAccessFile(file,"rw")) {
			EtchV3Header header=(EtchV3Header)AEtchHeader.open(data,file.getName(),secret);
			byte[] copyA=header.encode(header.generation()+1L,header.syncedFileEnd(),
					header.getRootHash(null),EtchConstants.V3_OPEN);
			byte[] copyB=header.encode(header.generation()+2L,header.syncedFileEnd(),
					header.getRootHash(null),EtchConstants.V3_OPEN);
			data.seek(EtchConstants.V3_HEADER_A_OFFSET);
			data.write(copyA);
			data.seek(EtchConstants.V3_HEADER_B_OFFSET);
			data.write(copyB);
			data.getChannel().force(true);
		} finally {
			if (secret!=null) Arrays.fill(secret,(byte)0);
		}
	}

	private static Map<String,byte[]> snapshotRelated(File base) throws IOException {
		Map<String,byte[]> snapshot=new LinkedHashMap<>();
		File[] files=base.getParentFile().listFiles((dir,name)->name.startsWith(base.getName()));
		if (files==null) return snapshot;
		Arrays.sort(files,(a,b)->a.getName().compareTo(b.getName()));
		for (File file:files) {
			if (file.isFile()) snapshot.put(file.getName(),Files.readAllBytes(file.toPath()));
		}
		return snapshot;
	}

	private static void assertSnapshotsEqual(Map<String,byte[]> expected,
			Map<String,byte[]> actual) {
		assertEquals(expected.keySet(),actual.keySet(),"GC recovery changed sibling files");
		for (String name:expected.keySet()) {
			assertArrayEquals(expected.get(name),actual.get(name),"GC recovery changed "+name);
		}
	}

	private static void markRelatedForDeletion(File base) {
		File[] files=base.getParentFile().listFiles((dir,name)->name.startsWith(base.getName()));
		if (files==null) return;
		for (File file:files) file.deleteOnExit();
	}

	private record MatrixCase(String name, EtchConfig config) {
		@Override
		public String toString() {
			return name;
		}
	}
}
