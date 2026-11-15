package convex.etch;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import org.junit.jupiter.api.Test;

import convex.core.data.ACell;
import convex.core.data.AVector;
import convex.core.data.Cells;
import convex.core.data.Hash;
import convex.core.exceptions.MissingDataException;

/** Failure boundaries and snapshot retention for copying GC. */
public class EtchGCRecoveryTest {
	private static File base() throws IOException {
		File file=File.createTempFile("etch-gc-recovery", ".etch");
		file.deleteOnExit();
		return file;
	}

	@Test
	public void testMissingRootCannotCompleteSweep() throws IOException {
		try (EtchStore store=EtchStore.create(base())) {
			Hash missing=EtchGCLifecycleTest.nonEmbedded(8000).getHash();
			store.getEtch().setRootHash(missing);
			store.startGC();
			store.getTargetEtch().getFile().deleteOnExit();
			assertEquals(missing,assertThrows(MissingDataException.class,store::transferGC).getMissingHash());
			assertFalse(store.isGCComplete());
			assertThrows(IllegalStateException.class,store::completeGC);
			assertFalse(EtchUtils.markerFile(store.getBaseFile()).exists());
		}
	}

	@Test
	public void testCutoverVerifiesTargetEvenAfterSuccessfulSweep() throws IOException {
		try (EtchStore store=EtchStore.create(base())) {
			store.setRootData(EtchGCLifecycleTest.tree(8100));
			store.startGC();
			store.transferGC();
			Hash missing=EtchGCLifecycleTest.nonEmbedded(8200).getHash();
			store.getTargetEtch().setRootHash(missing);
			store.getTargetEtch().getFile().deleteOnExit();
			assertEquals(missing,assertThrows(MissingDataException.class,store::completeGC).getMissingHash());
			assertFalse(EtchUtils.markerFile(store.getBaseFile()).exists());
		}
	}

	@Test
	public void testMalformedMarkerWithoutBaseDoesNotCreateEmptyStore() throws IOException {
		File base=base();
		assertTrue(base.delete());
		File target=new File(base.getPath()+"~");
		target.deleteOnExit();
		try (EtchStore store=EtchStore.create(target)) {
			store.setRootData(EtchGCLifecycleTest.tree(8300));
		}
		Path marker=EtchUtils.markerFile(base).toPath();
		marker.toFile().deleteOnExit();
		Files.writeString(marker, "");
		byte[] before=Files.readAllBytes(target.toPath());
		assertThrows(IOException.class,()->EtchStore.create(base));
		assertFalse(base.exists());
		assertTrue(Files.exists(marker));
		assertArrayEquals(before,Files.readAllBytes(target.toPath()));
	}

	@Test
	public void testFailedMarkerPublicationCanCancelAndRetry() throws IOException {
		File base=base();
		EtchStore store=EtchStore.create(base);
		Path marker=EtchUtils.markerFile(base).toPath();
		try {
			store.setRootData(EtchGCLifecycleTest.tree(8400));
			store.startGC();
			store.transferGC();
			// A non-empty directory cannot be atomically replaced by the marker.
			Files.createDirectory(marker);
			Path obstacle=Files.writeString(marker.resolve("obstacle"),"retain");
			File backup=new File(base.getPath()+".snapshot");
			assertThrows(IOException.class,()->store.completeGC(backup));
			assertFalse(backup.exists());
			assertTrue(store.isGCInProgress());
			assertFalse(new File(base.getPath()+".gc-defunct").exists());
			store.cancelGC();
			Files.delete(obstacle);
			Files.delete(marker);
			store.startGC();
			store.transferGC();
			try (EtchStore next=store.completeGC()) {
				assertEquals(store.getRootHash(),next.getRootHash());
			}
		} finally {
			store.close();
			marker.toFile().deleteOnExit();
		}
	}

	@Test
	public void testSnapshotRetainsPreCycleRootAcrossRecoveryAndLaterGC() throws IOException {
		File base=base();
		File backup=new File(base.getPath()+".snapshot");
		backup.deleteOnExit();
		AVector<ACell> oldRoot=EtchGCLifecycleTest.tree(8500);
		AVector<ACell> newRoot=EtchGCLifecycleTest.tree(8600);
		ACell garbage=EtchGCLifecycleTest.nonEmbedded(8700);
		EtchStore store=EtchStore.create(base);
		store.setRootData(oldRoot);
		Cells.persist(garbage,store);
		store.startGC();
		store.setRootData(newRoot);
		store.transferGC();
		try (EtchStore next=store.completeGC(backup)) {
			assertTrue(Files.isSameFile(base.toPath(),backup.toPath()),"Snapshot must retain the old file without copying");
			store.close();
			assertEquals(newRoot,next.getRootData());
			assertNull(next.getEtch().read(garbage.getHash()));
		} finally {
			store.close();
		}
		try (EtchStore snapshot=EtchStore.create(backup)) {
			assertEquals(oldRoot,snapshot.getRootData());
			assertNotNull(snapshot.getEtch().read(garbage.getHash()));
		}
		try (EtchStore live=EtchStore.create(base)) {
			assertEquals(newRoot,live.getRootData());
			live.startGC();
			live.transferGC();
			try (EtchStore latest=live.completeGC()) {
				assertEquals(newRoot,latest.getRootData());
			}
		}
		try (EtchStore snapshot=EtchStore.create(backup)) {
			assertEquals(oldRoot,snapshot.getRootData());
			assertNotNull(snapshot.getEtch().read(garbage.getHash()));
		}
	}

	@Test
	public void testBackupCannotOverwriteFilesOrUseGCNames() throws IOException {
		File base=base();
		File backup=new File(base.getPath()+".snapshot");
		backup.deleteOnExit();
		Files.writeString(backup.toPath(),"existing backup");
		try (EtchStore store=EtchStore.create(base)) {
			store.setRootData(EtchGCLifecycleTest.tree(8800));
			store.startGC();
			store.transferGC();
			assertThrows(IOException.class,()->store.completeGC(backup));
			assertThrows(IOException.class,()->store.completeGC(new File(base.getPath()+"~99")));
			assertThrows(IOException.class,()->store.completeGC(EtchUtils.markerFile(base)));
			assertEquals("existing backup",Files.readString(backup.toPath()));
			assertTrue(store.isGCInProgress());
			store.cancelGC();
		}
	}

	@Test
	public void testRecoveryAdoptsFilesItHasNotMapped() throws IOException {
		// Stores are written under other names and copied into place, so nothing
		// in this process has mapped the files recovery deletes and renames
		File base=base();
		File target=new File(base.getPath()+"~");
		File tombstone=new File(base.getPath()+".gc-defunct");
		File marker=EtchUtils.markerFile(base);
		target.deleteOnExit();
		tombstone.deleteOnExit();
		marker.deleteOnExit();
		File superseded=base();
		File collected=base();
		AVector<ACell> collectedRoot=EtchGCLifecycleTest.tree(9000);
		try (EtchStore store=EtchStore.create(superseded)) {
			store.setRootData(EtchGCLifecycleTest.tree(9100));
		}
		try (EtchStore store=EtchStore.create(collected)) {
			store.setRootData(collectedRoot);
		}
		Files.copy(superseded.toPath(),base.toPath(),StandardCopyOption.REPLACE_EXISTING);
		Files.copy(collected.toPath(),target.toPath());
		Files.writeString(tombstone.toPath(),"");
		EtchUtils.writeMarker(base,target);

		// The backend whose mappings outlive close
		EtchConfig config=EtchConfig.create(EtchConstants.CURRENT_VERSION,
				EtchConfig.MappingMode.MAPPED_BYTE_BUFFER,EtchConstants.DEFAULT_BUILD_CHAINS);
		assertEquals(base.getCanonicalFile(),EtchUtils.recover(base,config));
		assertFalse(target.exists());
		assertFalse(tombstone.exists());
		assertFalse(marker.exists());
		try (EtchStore store=EtchStore.create(base,config)) {
			assertEquals(collectedRoot.getHash(),store.getRootHash());
		}
	}

	@Test
	public void testCurrentStoreFileFollowsPendingAdoption() throws IOException {
		File base=base();
		File canonical=base.getCanonicalFile();
		assertEquals(canonical,EtchUtils.currentStoreFile(base));

		File target=new File(base.getPath()+"~");
		target.deleteOnExit();
		try (EtchStore store=EtchStore.create(target)) {
			store.setRootData(EtchGCLifecycleTest.tree(8900));
		}
		File marker=EtchUtils.markerFile(base);
		marker.deleteOnExit();
		EtchUtils.writeMarker(base,target);

		// Completed cutover, superseded base not yet deleted: resolution only reads
		assertEquals(target.getCanonicalFile(),EtchUtils.currentStoreFile(base));
		assertTrue(base.exists());
		assertTrue(marker.exists());

		// Superseded base deleted, collected file not yet renamed
		assertTrue(base.delete());
		assertEquals(target.getCanonicalFile(),EtchUtils.currentStoreFile(base));
		assertTrue(marker.exists());

		// A malformed marker leaves the base file current, as recovery treats it
		Files.writeString(marker.toPath(),"");
		assertEquals(canonical,EtchUtils.currentStoreFile(base));

		// A marker naming a lost file, with the base file also missing, is refused
		Files.writeString(marker.toPath(),base.getName()+"~7\n");
		assertThrows(IOException.class,()->EtchUtils.currentStoreFile(base));
	}
}
