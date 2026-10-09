package convex.etch;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import convex.core.data.ACell;
import convex.core.data.AVector;
import convex.core.data.AccountKey;
import convex.core.data.Cells;
import convex.core.data.Ref;
import convex.core.data.RefSoft;
import convex.core.data.Refs;

/** Tests configuration retention across EtchStore and GC file lifecycles. */
public class EtchConfiguredLifecycleTest {
	@ParameterizedTest
	@MethodSource("gcCases")
	public void testIndependentCheckpoint(MatrixCase c, boolean collecting) throws IOException {
		EtchConfig config=c.config().withRefCacheSize(19).withL2Enabled(false);
		File output=File.createTempFile("etch-checkpoint-matrix",".etch");
		assertTrue(output.delete());
		output.deleteOnExit();
		AVector<ACell> root=EtchGCLifecycleTest.tree(28000);
		try (EtchStore source=EtchStore.createTemp(config)) {
			if (collecting) source.startGC();
			for (int i=0; i<root.count(); i++) {
				source.storeTopRef(root.getRef(i),i==0?Ref.ANNOUNCED:Ref.STORED,null);
			}
			source.storeTopRef(root.getRef().withMinimumStatus(Ref.ANNOUNCED),Ref.STORED,null);
			if (collecting) source.getTargetEtch().write(root.getHash(),root.getRef().withStatus(Ref.ANNOUNCED));
			ACell live=EtchGCLifecycleTest.tree(29000);
			source.setRootData(live);
			Ref<?> sourceRef=source.refForHash(root.getHash()); // warm source cache
			assertEquals(output.getCanonicalFile(),source.exportCheckpoint(root.getHash(),output));
			assertSame(sourceRef,source.checkCache(root.getHash()));
			assertEquals(config,source.getConfig());
			assertEquals(live.getHash(),source.getRootHash());
			try (EtchStore restored=EtchStore.create(output,config)) {
				assertEquals(root,restored.getRootData());
				assertEquals(config,restored.getConfig());
				assertEquals(collecting?Ref.ANNOUNCED:Ref.PERSISTED,restored.getEtch().read(root.getHash()).getStatus());
				for (int i=0; i<root.count(); i++) {
					assertEquals(i==0?Ref.ANNOUNCED:Ref.PERSISTED,
							restored.getEtch().read(root.getRef(i).getHash()).getStatus());
				}
				assertNull(restored.getEtch().read(live.getHash()));
				EtchVerifier.verifyPersisted(restored.getEtch());
			}
			if (collecting) source.cancelGC();
			if (config.getVersion()==EtchConstants.VERSION_3) {
				source.flush();
				assertFalse(Arrays.equals(readV3Salt(source.getFile(),config),readV3Salt(output,config)));
				if (config.getCipherMode()!=EtchConfig.CipherMode.NONE) {
					EtchConfig wrong=config.withKeyFunction(h->WRONG_SECRET.clone());
					assertThrows(IOException.class,()->EtchStore.create(output,wrong));
				}
			}
		}
		try (EtchStore restored=EtchStore.create(output,config)) {
			assertEquals(root,restored.getRootData());
		}
	}

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

	@ParameterizedTest(name="{0}, snapshot={1}")
	@MethodSource("gcCases")
	@Execution(ExecutionMode.CONCURRENT)
	public void testConfiguredCompletedGCRecoveryMatrix(MatrixCase matrixCase, boolean snapshot)
			throws IOException {
		int seed=1000;
		File base=File.createTempFile("etch-config-gc-"+matrixCase.name(),".etch");
		base.deleteOnExit();
		File backup=snapshot?new File(base.getPath()+".snapshot"):null;
		if (backup!=null) backup.deleteOnExit();
		AVector<ACell> expected=EtchGCLifecycleTest.tree(seed);
		AVector<ACell> previousRoot=EtchGCLifecycleTest.tree(seed-10);
		AVector<ACell> garbage=EtchGCLifecycleTest.tree(seed-20);
		EtchConfig config=matrixCase.config().withRefCacheSize(37).withL2Enabled(false);
		EtchStore old=EtchStore.create(base,config);
		assertEquals(37,old.getRefCacheSize());
		assertFalse(old.isL2Enabled());
		EtchStore successor=null;
		try {
			old.setRootData(garbage);
			old.setRootData(previousRoot);
			old.startGC();
			old.setRootData(expected);
			old.transferGC();
			successor=old.completeGC(backup);
			assertEquals(config,successor.getConfig());
			assertEquals(config,successor.getEtch().getConfig());
			assertEquals(37,successor.getRefCacheSize());
			assertFalse(successor.isL2Enabled());
			assertTrue(base.exists()); // retained until the caller closes the legacy view
			assertTrue(new File(base.getPath()+".gc-defunct").exists());
			assertNotNull(old.getEtch().read(garbage.getHash()));
			old.close();
			EtchGCLifecycleTest.assertRetiredFileDeleted(old.getEtch());
			old.close(); // duplicate close must not affect the successor or snapshot
			assertEquals(expected,successor.getRootData());
			assertNull(successor.getEtch().read(previousRoot.getHash()));
			assertNull(successor.getEtch().read(garbage.getHash()));
			successor.setRootData(expected.assoc(0,expected));
			successor.setRootData(expected);
		} finally {
			if (successor!=null) successor.close();
			old.close();
		}

		try (EtchStore reopened=EtchStore.create(base,config)) {
			assertEquals(expected.getHash(),reopened.getRootHash(),matrixCase.name());
			assertEquals(expected,reopened.getRootData(),matrixCase.name());
			assertEquals(config,reopened.getConfig(),matrixCase.name());
			assertEquals(37,reopened.getRefCacheSize());
			assertFalse(reopened.isL2Enabled());
		}
		if (backup!=null) {
			try (EtchStore saved=EtchStore.create(backup,config)) {
				assertEquals(previousRoot,saved.getRootData());
				assertEquals(garbage,saved.getEtch().read(garbage.getHash()).getValue());
				assertNull(saved.getEtch().read(expected.getHash()));
				assertEquals(config,saved.getConfig());
				assertEquals(37,saved.getRefCacheSize());
				assertFalse(saved.isL2Enabled());
			}
		}
		markRelatedForDeletion(base);
	}

	@ParameterizedTest(name="{0}, oldest closes first={1}")
	@MethodSource("gcCases")
	@Execution(ExecutionMode.CONCURRENT)
	public void testSuccessiveGCWithLegacyViews(MatrixCase matrixCase, boolean oldestFirst) throws IOException {
		EtchConfig config=matrixCase.config().withRefCacheSize(1).withL2Enabled(false);
		File base=File.createTempFile("etch-generations-"+matrixCase.name(),".etch");
		File backup=new File(base.getPath()+".snapshot");
		AVector<ACell> intermediateOnly=EtchGCLifecycleTest.tree(11000);
		AVector<ACell> cancelledRoot=EtchGCLifecycleTest.tree(12000);
		AVector<ACell> finalRoot=EtchGCLifecycleTest.tree(13000);
		try (EtchStore a=EtchStore.create(base,config)) {
			a.setRootData(EtchGCLifecycleTest.tree(10000));
			a.startGC();
			// This value lives only in B's file, but refs issued now belong to A.
			a.storeTopRef(intermediateOnly.getRef(),Ref.ANNOUNCED,null);
			a.transferGC();
			try (EtchStore b=a.completeGC()) {
				b.startGC();
				b.transferGC();
				a.setRootData(cancelledRoot);
				assertEquals(cancelledRoot.getHash(),b.getRootHash());
				assertNotNull(b.getTargetEtch().read(cancelledRoot.getHash()));
				assertNull(b.getEtch().read(cancelledRoot.getHash()));
				b.cancelGC();
				assertEquals(cancelledRoot,a.getRootData());
				assertEquals(cancelledRoot,b.getRootData());
				assertNotNull(b.getEtch().read(cancelledRoot.getHash()));

				b.startGC();
				b.transferGC();
				a.setRootData(finalRoot); // #746: must reach C after B's sweep
				ACell fromB=EtchGCLifecycleTest.nonEmbedded(14000);
				Cells.persist(fromB,b);
				assertNull(b.getEtch().read(finalRoot.getHash()));
				try (EtchStore c=b.completeGC(backup)) {
					assertEquals(finalRoot,c.getRootData());
					assertNotNull(c.getEtch().read(fromB.getHash()));
					assertNull(c.refForHash(intermediateOnly.getHash()));
					Ref<ACell> throughA=a.readStoreRef(fromB.getHash());
					assertSame(a,((RefSoft<?>)throughA).getStore());
					assertSame(a,((RefSoft<?>)a.checkCache(fromB.getHash())).getStore());
					assertThrows(IllegalStateException.class,a::startGC);
					assertThrows(IllegalStateException.class,b::cancelGC);

					EtchStore first=oldestFirst?a:b;
					EtchStore remaining=oldestFirst?b:a;
					first.close();
					first.close();
					assertThrows(IOException.class,()->first.readStoreRef(finalRoot.getHash()));
					assertThrows(IOException.class,()->first.setRootData(finalRoot));
					// Even a closed B must retain its file for an open A's cold refs.
					assertTrue(b.getFile().exists());
					Ref<ACell> retained=remaining.readStoreRef(intermediateOnly.getHash());
					assertSame(remaining,((RefSoft<?>)retained).getStore());
					assertEquals(intermediateOnly,retained.getValue());
					Refs.checkConsistentStores(retained,remaining);
					assertEquals(Ref.ANNOUNCED,retained.getStatus());

					// A further cycle must neither delete nor reuse the pinned B file.
					c.startGC();
					assertFalse(c.getTargetEtch().getFile().equals(b.getFile()));
					remaining.setRootData(finalRoot);
					c.cancelGC();
					assertEquals(finalRoot,c.getRootData());
					assertEquals(intermediateOnly,remaining.readStoreRef(intermediateOnly.getHash()).getValue());
					remaining.flush();
					remaining.close();
					EtchGCLifecycleTest.assertRetiredFileDeleted(a.getEtch());
					EtchGCLifecycleTest.assertRetiredFileDeleted(b.getEtch());
					assertEquals(finalRoot,c.getRootData());
				}
			}
		}
		try (EtchStore snapshot=EtchStore.create(backup,config)) {
			assertEquals(cancelledRoot,snapshot.getRootData());
			assertEquals(intermediateOnly,snapshot.readStoreRef(intermediateOnly.getHash()).getValue());
			assertNull(snapshot.readStoreRef(finalRoot.getHash()));
		}
		try (EtchStore reopened=EtchStore.create(base,config)) {
			assertEquals(finalRoot,reopened.getRootData());
			assertNull(reopened.readStoreRef(intermediateOnly.getHash()));
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

	@Test
	public void testEncryptedEtchCloseDestroysCryptoAndDeregistersHook() throws IOException {
		EtchConfig config=encryptedConfig(EtchConfig.CipherMode.AES_256_CTR,true,SECRET);
		File file=File.createTempFile("etch-config-crypto-close",".etch");
		file.deleteOnExit();
		Etch etch=Etch.create(file,config);
		assertTrue(etch.hasShutdownHook());
		assertFalse(etch.isCryptoDestroyed());
		etch.close();
		assertFalse(etch.hasShutdownHook());
		assertTrue(etch.isCryptoDestroyed());
	}

	@Test
	public void testMaintenanceCloseDestroysCrypto() throws IOException {
		EtchConfig config=encryptedConfig(EtchConfig.CipherMode.CHACHA20,true,SECRET);
		File file=File.createTempFile("etch-config-maintenance-close",".etch");
		file.deleteOnExit();
		try (EtchStore store=EtchStore.create(file,config)) {
			store.setRootData(EtchGCLifecycleTest.tree(5000));
		}
		EtchMaintenanceReader reader=EtchMaintenanceReader.openUnsafe(file,config);
		assertFalse(reader.isCryptoDestroyed());
		reader.close();
		assertTrue(reader.isCryptoDestroyed());
	}

	@Test
	public void testCompletedGCTransfersCipherOwnershipToSuccessor() throws IOException {
		EtchConfig config=encryptedConfig(EtchConfig.CipherMode.AES_256_CTR,true,SECRET);
		File file=File.createTempFile("etch-config-gc-crypto-owner",".etch");
		file.deleteOnExit();
		EtchStore old=EtchStore.create(file,config);
		EtchStore successor=null;
		Etch transferred=null;
		try {
			AVector<ACell> root=EtchGCLifecycleTest.tree(6000);
			old.setRootData(root);
			old.startGC();
			old.transferGC();
			successor=old.completeGC();
			transferred=successor.getEtch();
			old.close();
			assertFalse(transferred.isCryptoDestroyed());
			assertEquals(root,successor.getRootData());
			successor.close();
			assertTrue(transferred.isCryptoDestroyed());
			successor=null;
		} finally {
			if (successor!=null) successor.close();
			old.close();
		}
		markRelatedForDeletion(file);
	}

	@Test
	public void testClosingActiveGCClosesBothCipherOwners() throws IOException {
		EtchConfig config=encryptedConfig(EtchConfig.CipherMode.CHACHA20,true,SECRET);
		File file=File.createTempFile("etch-config-gc-crypto-close",".etch");
		file.deleteOnExit();
		EtchStore store=EtchStore.create(file,config);
		Etch base=store.getEtch();
		store.startGC();
		Etch target=store.getTargetEtch();
		store.close();
		assertTrue(base.isCryptoDestroyed());
		assertTrue(target.isCryptoDestroyed());
		assertFalse(base.hasShutdownHook());
		assertFalse(target.hasShutdownHook());
		markRelatedForDeletion(file);
	}

	private static Stream<Arguments> gcCases() {
		return matrixCases().stream().flatMap(c->Stream.of(Arguments.of(c,false),Arguments.of(c,true)));
	}

	private static List<MatrixCase> matrixCases() {
		EtchConfig.MappingMode mapping=EtchConfig.create(EtchConstants.VERSION_3).getMappingMode();
		return List.of(
				new MatrixCase("v1",EtchConfig.create(EtchConstants.VERSION_1)),
				new MatrixCase("v2",EtchConfig.create(EtchConstants.VERSION_2)),
				new MatrixCase("v2-mbb",EtchConfig.create(EtchConstants.VERSION_2,
						EtchConfig.MappingMode.MAPPED_BYTE_BUFFER,EtchConstants.DEFAULT_BUILD_CHAINS)),
				new MatrixCase("v3-plain",EtchConfig.create(EtchConstants.VERSION_3)),
				new MatrixCase("v3-mbb",EtchConfig.create(EtchConstants.VERSION_3,
						EtchConfig.MappingMode.MAPPED_BYTE_BUFFER,EtchConstants.DEFAULT_BUILD_CHAINS)),
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
		try (RandomAccessFile data=new RandomAccessFile(file,"r");
				AFileMapper mapper=new MBBFileMapper(data.getChannel(),true)) {
			AEtchHeader header=AEtchHeader.open(mapper,file.getName(),secret);
			return ((EtchV3Header)header).fileSalt();
		} finally {
			if (secret!=null) Arrays.fill(secret,(byte)0);
		}
	}

	private static void markV3Open(File file, EtchConfig config) throws IOException {
		byte[] secret=(config.getCipherMode()==EtchConfig.CipherMode.NONE)?null
				:config.resolveKey(config.getPublicKeyHint());
		try (RandomAccessFile data=new RandomAccessFile(file,"rw")) {
			EtchV3Header header;
			try (AFileMapper mapper=new MBBFileMapper(data.getChannel(),true)) {
				header=(EtchV3Header)AEtchHeader.open(mapper,file.getName(),secret);
			}
			byte[] copyA=header.encode(header.generation()+1L,header.syncedFileEnd(),
					header.getRootHash(),EtchConstants.V3_OPEN);
			byte[] copyB=header.encode(header.generation()+2L,header.syncedFileEnd(),
					header.getRootHash(),EtchConstants.V3_OPEN);
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
