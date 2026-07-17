package convex.dlfs.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import convex.core.data.ACell;
import convex.core.data.AHashMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Strings;
import convex.etch.EtchStore;
import convex.lattice.cursor.ALatticeCursor;
import convex.lattice.cursor.Cursors;
import convex.lattice.cursor.RootLatticeCursor;
import convex.lattice.fs.DLFSLattice;
import convex.lattice.fs.DLFSNode;
import convex.lattice.generic.MapLattice;
import convex.dlfs.DLFSDriveManager;
import convex.node.NodeConfig;
import convex.node.NodeServer;

public class DLFSDriveManagerTest {

	private static ALatticeCursor<AHashMap<AString, AVector<ACell>>> createCursor() {
		MapLattice<AString, AVector<ACell>> lattice=MapLattice.create(DLFSLattice.INSTANCE);
		return Cursors.createLattice(lattice);
	}

	@Test
	public void testCursorBackedRegistrySurvivesManagerReconstruction() throws Exception {
		ALatticeCursor<AHashMap<AString, AVector<ACell>>> cursor=createCursor();
		DLFSDriveManager first=new DLFSDriveManager(cursor);

		assertTrue(first.createDrive(null, "home"));
		FileSystem home=first.getDrive(null, "home");
		assertNotNull(home);
		Files.writeString(home.getPath("/hello.txt"), "persistent view");
		((convex.lattice.fs.DLFileSystem)home).sync();

		DLFSDriveManager reconstructed=new DLFSDriveManager(cursor);
		assertEquals(List.of("home"), reconstructed.listDrives(null));
		FileSystem restored=reconstructed.getDrive(null, "home");
		assertNotNull(restored);
		assertEquals("persistent view", Files.readString(restored.getPath("/hello.txt")));
	}

	@Test
	public void testCursorBackedRenameAndDeleteChangeCanonicalRegistry() throws Exception {
		ALatticeCursor<AHashMap<AString, AVector<ACell>>> cursor=createCursor();
		DLFSDriveManager manager=new DLFSDriveManager(cursor);
		assertTrue(manager.createDrive(null, "before"));
		Files.writeString(manager.getDrive(null, "before").getPath("/data"), "kept");

		assertTrue(manager.renameDrive(null, "before", "after"));
		assertNull(cursor.get().get(Strings.create("before")));
		assertNotNull(cursor.get().get(Strings.create("after")));
		assertEquals("kept", Files.readString(manager.getDrive(null, "after").getPath("/data")));

		assertTrue(manager.deleteDrive(null, "after"));
		assertFalse(cursor.get().containsKey(Strings.create("after")));
		assertTrue(new DLFSDriveManager(cursor).listDrives(null).isEmpty());
	}

	@Test
	public void testCursorBackedRegistryDiscoversExternalDrive() {
		ALatticeCursor<AHashMap<AString, AVector<ACell>>> cursor=createCursor();
		DLFSDriveManager manager=new DLFSDriveManager(cursor);
		AString remote=Strings.create("remote");

		cursor.updateAndGet(drives->drives.assoc(remote, DLFSNode.createDirectory(convex.core.data.prim.CVMLong.ZERO)));

		assertEquals(List.of("remote"), manager.listDrives(null));
		assertNotNull(manager.getDrive(null, "remote"));
	}

	@Test
	public void testConcurrentCreateHasSingleWinner() throws Exception {
		ALatticeCursor<AHashMap<AString, AVector<ACell>>> cursor=createCursor();
		DLFSDriveManager first=new DLFSDriveManager(cursor);
		DLFSDriveManager second=new DLFSDriveManager(cursor);
		CountDownLatch start=new CountDownLatch(1);

		try (ExecutorService executor=Executors.newFixedThreadPool(2)) {
			Future<Boolean> a=executor.submit(()->{ start.await(); return first.createDrive(null, "same"); });
			Future<Boolean> b=executor.submit(()->{ start.await(); return second.createDrive(null, "same"); });
			start.countDown();
			assertTrue(a.get() ^ b.get(), "exactly one concurrent creator must report success");
		}
		assertEquals(List.of("same"), first.listDrives(null));
	}

	@Test
	@SuppressWarnings("unchecked")
	public void testRegistrySyncIsExplicit() {
		MapLattice<AString, AVector<ACell>> lattice=MapLattice.create(DLFSLattice.INSTANCE);
		RootLatticeCursor<AHashMap<AString, AVector<ACell>>> cursor=
			(RootLatticeCursor<AHashMap<AString, AVector<ACell>>>)Cursors.createLattice(lattice);
		AtomicReference<ACell> synced=new AtomicReference<>();
		cursor.onSync(value->{ synced.set(value); return value; });
		DLFSDriveManager manager=new DLFSDriveManager(cursor);

		assertTrue(manager.createDrive(null, "home"));
		assertNull(synced.get(), "in-memory registry mutation must not perform I/O implicitly");
		manager.sync();

		AHashMap<AString, AVector<ACell>> value=(AHashMap<AString, AVector<ACell>>)synced.get();
		assertNotNull(value);
		assertNotNull(value.get(Strings.create("home")));
	}

	@Test
	public void testEtchRestartRestoresRegistryAndContents() throws Exception {
		MapLattice<AString, AVector<ACell>> lattice=MapLattice.create(DLFSLattice.INSTANCE);
		try (EtchStore store=EtchStore.createTemp("dlfs-registry")) {
			NodeServer<AHashMap<AString, AVector<ACell>>> first=
				new NodeServer<>(lattice, store, NodeConfig.port(-1));
			first.launch();
			try {
				DLFSDriveManager manager=new DLFSDriveManager(first.getCursor());
				assertTrue(manager.createDrive(null, "home"));
				Files.writeString(manager.getDrive(null, "home").getPath("/hello.txt"), "after restart");
				manager.sync();
			} finally {
				first.close();
			}

			NodeServer<AHashMap<AString, AVector<ACell>>> restored=
				new NodeServer<>(lattice, store, NodeConfig.port(-1));
			restored.launch();
			try {
				DLFSDriveManager manager=new DLFSDriveManager(restored.getCursor());
				assertEquals(List.of("home"), manager.listDrives(null));
				assertEquals("after restart",
					Files.readString(manager.getDrive(null, "home").getPath("/hello.txt")));
			} finally {
				restored.close();
			}
		}
	}
}
