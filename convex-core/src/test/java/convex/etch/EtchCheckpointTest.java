package convex.etch;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.AccountKey;
import convex.core.data.Hash;
import convex.core.data.Maps;
import convex.core.data.Ref;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;
import convex.core.data.prim.CVMBool;
import convex.core.exceptions.MissingDataException;

public class EtchCheckpointTest {
	private static File output() throws IOException {
		File file=File.createTempFile("etch-checkpoint-test",".etch");
		assertTrue(file.delete());
		file.deleteOnExit();
		return file;
	}

	@Test
	public void testRootsAndRepeatedExport() throws IOException {
		try (EtchStore source=EtchStore.createTemp()) {
			for (Hash root:new Hash[] {Hash.NULL_HASH,Hash.UNSET_HASH,Hash.EMPTY_HASH}) {
				File file=source.exportCheckpoint(root,output());
				try (EtchStore restored=EtchStore.create(file)) {
					assertEquals(root,restored.getRootHash());
					assertNull(restored.getRootData());
				}
			}
			ACell embedded=CVMLong.create(777);
			source.setRootData(embedded);
			File first=source.exportCheckpoint(embedded.getHash(),output());
			source.setRootData(EtchGCLifecycleTest.tree(31000));
			File second=source.exportCheckpoint(embedded.getHash(),output());
			source.startGC();
			source.transferGC();
			try (EtchStore next=source.completeGC()) {
				source.close();
				for (File file:new File[] {first,second}) {
					try (EtchStore restored=EtchStore.create(file)) { assertEquals(embedded,restored.getRootData()); }
				}
			}
		}
	}

	@Test
	public void testMissingAndOccupiedDestinations() throws IOException {
		try (EtchStore source=EtchStore.createTemp()) {
			AVector<ACell> root=EtchGCLifecycleTest.tree(32000);
			File destination=output();
			assertThrows(MissingDataException.class,()->source.exportCheckpoint(root.getHash(),destination));
			source.storeTopRef(root.getRef(),Ref.STORED,null); // missing descendants
			assertThrows(MissingDataException.class,()->source.exportCheckpoint(root.getHash(),destination));
			assertFalse(destination.exists());
			source.setRootData(root);
			Files.createFile(destination.toPath());
			assertThrows(IOException.class,()->source.exportCheckpoint(root.getHash(),destination));
			assertEquals(0,destination.length());
			assertThrows(IOException.class,()->source.exportCheckpoint(root.getHash(),source.getFile()));
			File reserved=new File(source.getBaseFile()+"~99");
			assertThrows(IOException.class,()->source.exportCheckpoint(root.getHash(),reserved));
			File sidecarOutput=output();
			Path marker=Path.of(sidecarOutput+".gc-complete");
			Files.writeString(marker,"do not change\n");
			try { assertThrows(IOException.class,()->source.exportCheckpoint(root.getHash(),sidecarOutput)); }
			finally { Files.delete(marker); }
			assertEquals(root,source.getRootData());
		}
	}

	@ParameterizedTest
	@MethodSource("leaseCases")
	@Timeout(30)
	public void testLeaseAcrossCancelCutoversAndClose(EtchConfig config, boolean cancel) throws Exception {
		EtchStore source=EtchStore.createTemp(config);
		EtchStore current=source;
		File destination=output();
		CountDownLatch copied=new CountDownLatch(1);
		CountDownLatch resume=new CountDownLatch(1);
		AtomicBoolean first=new AtomicBoolean(true);
		AVector<ACell> root=EtchGCLifecycleTest.tree(33000);
		try (var executor=Executors.newSingleThreadExecutor()) {
			source.startGC();
			source.setRootData(root); // chosen graph lives only in the active target
			Etch pinned=source.getTargetEtch();
			var result=executor.submit(()->EtchCheckpoint.export(source,root.getHash(),destination,source.getConfig(),new EtchCheckpoint.IO() {
				@Override void copied(Hash hash) throws IOException {
					if (first.getAndSet(false)) {
						copied.countDown();
						await(resume);
					}
				}
			}));
			try {
				assertTrue(copied.await(10,TimeUnit.SECONDS));
				if (cancel) source.cancelGC();
				else {
					source.transferGC();
					current=source.completeGC();
					source.close();
				}
				for (int i=0;i<2;i++) {
					current.setRootData(EtchGCLifecycleTest.tree(34000+i*10));
					current.startGC();
					assertNotEquals(pinned.getFile(),current.getTargetEtch().getFile());
					current.transferGC();
					EtchStore next=current.completeGC();
					current.close();
					current=next;
				}
				assertNull(current.getEtch().read(root.getHash()));
				current.close(); // the private lease outlives all application handles
				assertTrue(pinned.getFile().exists());
			} finally { resume.countDown(); }
			assertEquals(destination.getCanonicalFile(),result.get(10,TimeUnit.SECONDS));
			EtchGCLifecycleTest.assertRetiredFileDeleted(pinned);
			try (EtchStore restored=EtchStore.create(destination,config)) { assertEquals(root,restored.getRootData()); }
		} finally {
			resume.countDown();
			current.close();
			source.close();
		}
	}

	static Stream<Arguments> leaseCases() {
		return Stream.of(EtchConfig.MappingMode.values()).flatMap(mapping-> {
			if (mapping==EtchConfig.MappingMode.MEMORY_SEGMENT && !EtchFileMapperFactory.isFFMAvailable()) return Stream.empty();
			return Stream.of(EtchConfig.create(EtchConstants.VERSION_2,mapping,true),
					EtchConfig.createV3(mapping,true,EtchConfig.CipherMode.AES_256_CTR,true,null,h->new byte[32]),
					EtchConfig.createV3(mapping,false,EtchConfig.CipherMode.CHACHA20,false,null,h->new byte[32]))
					.flatMap(config->Stream.of(Arguments.of(config,false),Arguments.of(config,true)));
		});
	}

	private static void await(CountDownLatch signal) throws IOException {
		try { if (!signal.await(10,TimeUnit.SECONDS)) throw new IOException("Timed out waiting for test signal"); }
		catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new InterruptedIOException(); }
	}

	@ParameterizedTest
	@ValueSource(strings={"copy","flush","close","publish","interrupt","cleanup"})
	public void testFailureBoundaries(String failure) throws IOException {
		try (EtchStore source=EtchStore.createTemp()) {
			ACell root=EtchGCLifecycleTest.tree(35000);
			source.setRootData(root);
			File destination=output();
			EtchCheckpoint.IO io=new EtchCheckpoint.IO() {
				void fail(String phase) throws IOException { if (failure.equals(phase)) throw new IOException("Injected "+phase); }
				@Override void copied(Hash hash) throws IOException {
					fail("copy");
					if (failure.equals("interrupt")) Thread.currentThread().interrupt();
				}
				@Override void flush(Etch e) throws IOException { fail("flush"); super.flush(e); }
				@Override void close(Etch e) throws IOException { super.close(e); fail("close"); }
				@Override void publish(Path dest, Path stage) throws IOException { fail("publish"); super.publish(dest,stage); }
				@Override void cleanup(Path stage) throws IOException { fail("cleanup"); super.cleanup(stage); }
			};
			try {
				if (failure.equals("cleanup")) {
					EtchCheckpoint.export(source,root.getHash(),destination,source.getConfig(),io);
					try (EtchStore restored=EtchStore.create(destination)) { assertEquals(root,restored.getRootData()); }
				} else {
					assertThrows(IOException.class,()->EtchCheckpoint.export(source,root.getHash(),destination,source.getConfig(),io));
					assertFalse(destination.exists());
					if (failure.equals("interrupt")) assertTrue(Thread.currentThread().isInterrupted());
				}
			} finally { Thread.interrupted(); }
			source.setRootData(CVMLong.create(123)); // failures release source leases
			assertEquals(CVMLong.create(123),source.getRootData());
		}
	}

	@Test
	public void testRealCloseFailureIsReported() throws Exception {
		try (EtchStore source=EtchStore.createTemp()) {
			ACell root=EtchGCLifecycleTest.tree(38000);
			source.setRootData(root);
			File destination=output();
			var field=Etch.class.getDeclaredField("data");
			field.setAccessible(true);
			IOException failure=assertThrows(IOException.class,()->EtchCheckpoint.export(source,root.getHash(),destination,source.getConfig(),new EtchCheckpoint.IO() {
				@Override void close(Etch file) throws IOException {
					try { ((RandomAccessFile)field.get(file)).getChannel().close(); }
					catch (IllegalAccessException e) { throw new AssertionError(e); }
					super.close(file); // Legacy header force must fail on the closed channel.
				}
			}));
			assertTrue(failure.getMessage().contains("Etch close failed"));
			assertFalse(destination.exists());
			assertEquals(root,source.getRootData());
		}
	}

	@Test
	@Timeout(30)
	public void testConcurrentPublicationAndStatusTiming() throws Exception {
		try (EtchStore source=EtchStore.createTemp(); var executor=Executors.newFixedThreadPool(2)) {
			AVector<ACell> root=EtchGCLifecycleTest.tree(39000);
			source.setRootData(root);
			File destination=output();
			CountDownLatch atPublication=new CountDownLatch(2);
			CountDownLatch publish=new CountDownLatch(1);
			AtomicBoolean statusUpgraded=new AtomicBoolean();
			EtchCheckpoint.IO io=new EtchCheckpoint.IO() {
				@Override void copied(Hash hash) throws IOException {
					if (statusUpgraded.compareAndSet(false,true)) {
						// This export has already sampled/written this leaf's status.
						source.storeTopRef(source.refForHash(hash),Ref.ANNOUNCED,null);
					}
				}
				@Override void publish(Path dest, Path stage) throws IOException {
					atPublication.countDown();
					await(publish);
					super.publish(dest,stage);
				}
			};
			java.util.concurrent.Callable<Boolean> export=()-> {
				try { EtchCheckpoint.export(source,root.getHash(),destination,source.getConfig(),io); return true; }
				catch (java.nio.file.FileAlreadyExistsException e) { return false; }
			};
			var first=executor.submit(export);
			var second=executor.submit(export);
			try { assertTrue(atPublication.await(10,TimeUnit.SECONDS)); }
			finally { publish.countDown(); }
			assertNotEquals(first.get(10,TimeUnit.SECONDS),second.get(10,TimeUnit.SECONDS));
			try (EtchStore restored=EtchStore.create(destination)) { assertEquals(root,restored.getRootData()); }
		}
	}

	@Test
	public void testPerEntryAnnouncementObservation() throws IOException {
		try (EtchStore source=EtchStore.createTemp()) {
			AVector<ACell> root=EtchGCLifecycleTest.tree(41000);
			source.setRootData(root);
			Hash[] sampled=new Hash[1];
			File result=EtchCheckpoint.export(source,root.getHash(),output(),source.getConfig(),new EtchCheckpoint.IO() {
				@Override void copied(Hash hash) throws IOException {
					if (sampled[0]!=null) return;
					sampled[0]=hash;
					source.storeTopRef(source.refForHash(hash),Ref.ANNOUNCED,null);
				}
			});
			assertEquals(Ref.ANNOUNCED,source.getEtch().read(sampled[0]).getStatus());
			try (EtchStore restored=EtchStore.create(result)) {
				assertEquals(Ref.PERSISTED,restored.getEtch().read(sampled[0]).getStatus(),
						"A later upgrade must not change the already sampled checkpoint");
			}
		}
	}

	@Test
	public void testDestinationAliases() throws IOException {
		try (EtchStore source=EtchStore.createTemp()) {
			source.setRootData(CVMLong.create(888));
			File alias=output();
			Files.createLink(alias.toPath(),source.getFile().toPath());
			assertThrows(IOException.class,()->source.exportCheckpoint(source.getRootHash(),alias));
			File symlink=output();
			File missing=output();
			try { Files.createSymbolicLink(symlink.toPath(),missing.toPath()); }
			catch (IOException | UnsupportedOperationException e) {
				org.junit.jupiter.api.Assumptions.abort("Symbolic links unavailable: "+e.getClass().getSimpleName());
			}
			try {
				assertThrows(IOException.class,()->source.exportCheckpoint(source.getRootHash(),symlink));
				assertTrue(Files.isSymbolicLink(symlink.toPath()));
				assertFalse(missing.exists());
			} finally { Files.deleteIfExists(symlink.toPath()); }
		}
	}

	@Test
	public void testLastLeaseClosesSource() throws IOException {
		EtchConfig config=EtchConfig.createV3(EtchConfig.create(EtchConstants.VERSION_3).getMappingMode(),
				true,EtchConfig.CipherMode.AES_256_CTR,true,null,h->new byte[32]);
		try (EtchStore source=EtchStore.createTemp(config)) {
			ACell root=EtchGCLifecycleTest.tree(42000);
			source.setRootData(root);
			try (EtchReadView first=source.lease(); EtchReadView second=source.lease()) {
				source.close();
				first.close();
				assertFalse(source.getEtch().isCryptoDestroyed());
				assertEquals(root,second.read(root.getHash()).getValue());
			}
			assertTrue(source.getEtch().isCryptoDestroyed());
		}
	}

	@Test
	public void testPartialConfigAndRekey() throws IOException {
		byte[] oldKey=new byte[32];
		byte[] newKey=new byte[32];
		newKey[0]=42;
		EtchConfig config=EtchConfig.createV3(EtchConfig.MappingMode.MAPPED_BYTE_BUFFER,true,
				EtchConfig.CipherMode.AES_256_CTR,true,null,h->oldKey).withRefCacheSize(37).withL2Enabled(false);
		try (EtchStore source=EtchStore.createTemp(config)) {
			ACell root=EtchGCLifecycleTest.tree(36000);
			source.setRootData(root);
			AccountKey newHint=AccountKey.wrap(newKey);
			AMap<AString,ACell> overrides=Maps.of(EtchConfig.REF_CACHE_SIZE,CVMLong.create(23),
					EtchConfig.CIPHER,Strings.create("chacha20"),EtchConfig.PUBLIC_KEY_HINT,Strings.create(newHint.toHexString()));
			assertEquals(config.getMap().merge(overrides),config.withOverrides(overrides).getMap());
			File rekeyed=source.exportCheckpoint(root.getHash(),output(),overrides,h->{ assertEquals(newHint,h); return newKey; });
			assertThrows(IOException.class,()->EtchStore.create(rekeyed,config));
			try (EtchStore restored=EtchStore.create(rekeyed,config.withOverrides(overrides,h->newKey))) {
				assertEquals(root,restored.getRootData());
				assertEquals(23,restored.getRefCacheSize());
				assertEquals(newHint,restored.getConfig().getPublicKeyHint());
				assertEquals(EtchConfig.CipherMode.CHACHA20,restored.getConfig().getCipherMode());
				assertFalse(restored.isL2Enabled());
			}
			File invalid=output();
			assertThrows(IllegalArgumentException.class,()->source.exportCheckpoint(root.getHash(),invalid,
					Maps.of(EtchConfig.VERSION,CVMLong.create(2))));
			assertFalse(invalid.exists());
			AMap<AString,ACell> plain=Maps.of(EtchConfig.VERSION,CVMLong.create(1),
					EtchConfig.CIPHER,Strings.create("none"),EtchConfig.ENCRYPT_INDEX,CVMBool.FALSE,
					EtchConfig.PUBLIC_KEY_HINT,null);
			File converted=source.exportCheckpoint(root.getHash(),output(),plain);
			try (EtchStore restored=EtchStore.create(converted)) { assertEquals(root,restored.getRootData()); }
			assertEquals(config,source.getConfig());
		}
	}

	@Test
	@Timeout(60)
	public void testDeepSharedStoredGraph() throws IOException {
		try (EtchStore source=EtchStore.createTemp()) {
			ACell leaf=EtchGCLifecycleTest.nonEmbedded(37000);
			source.storeTopRef(leaf.getRef(),Ref.STORED,null);
			ACell root=leaf;
			for (int i=0;i<4000;i++) {
				root=Vectors.of(root,leaf,leaf,leaf,leaf);
				source.storeTopRef(root.getRef(),Ref.STORED,null);
			}
			AtomicInteger copied=new AtomicInteger();
			File file=EtchCheckpoint.export(source,root.getHash(),output(),source.getConfig(),new EtchCheckpoint.IO() {
				@Override void copied(Hash hash) { copied.incrementAndGet(); }
			});
			assertEquals(4001,copied.get(),"Each shared subtree is copied once");
			try (EtchStore restored=EtchStore.create(file)) {
				assertEquals(root.getHash(),restored.getRootHash());
				EtchVerifier.verifyPersisted(restored.getEtch());
			}
		}
	}
}
