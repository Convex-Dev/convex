package convex.dlfs.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
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
import convex.core.data.prim.CVMLong;
import convex.etch.EtchStore;
import convex.lattice.cursor.ALatticeCursor;
import convex.lattice.LatticeContext;
import convex.lattice.cursor.Cursors;
import convex.lattice.cursor.RootLatticeCursor;
import convex.lattice.fs.DLFSLattice;
import convex.lattice.fs.DLFSNode;
import convex.lattice.generic.MapLattice;
import convex.dlfs.DLFSDriveManager;
import convex.dlfs.DLFSDrives;
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
	public void testRoutesIdentitiesToIndependentComponents() {
		DLFSDrives alice=DLFSDrives.create();
		DLFSDrives bob=DLFSDrives.create();
		DLFSDriveManager manager=DLFSDriveManager.createRouter()
			.mount("did:example:alice",alice)
			.mount("did:example:bob",bob);

		assertTrue(manager.createDrive("did:example:alice","home"));
		assertTrue(manager.createDrive("did:example:bob","work"));

		assertEquals(List.of("home"),manager.listDrives("did:example:alice"));
		assertEquals(List.of("work"),manager.listDrives("did:example:bob"));
		assertNull(manager.getDrive("did:example:alice","work"));
		assertNull(manager.getDrive("did:example:bob","home"));
	}

	@Test
	public void testRouterFailsClosedForUnmountedIdentity() {
		DLFSDriveManager manager=DLFSDriveManager.createRouter()
			.mount("did:example:alice",DLFSDrives.create());

		assertFalse(manager.createDrive("did:example:bob","home"));
		assertEquals(List.of(),manager.listDrives("did:example:bob"));
		assertNull(manager.getDrive("did:example:bob","home"));
	}

	@Test
	public void testCursorBackedRenameAndDeleteChangeCanonicalRegistry() throws Exception {
		ALatticeCursor<AHashMap<AString, AVector<ACell>>> cursor=createCursor();
		DLFSDriveManager manager=new DLFSDriveManager(cursor);
		assertTrue(manager.createDrive(null, "before"));
		Files.writeString(manager.getDrive(null, "before").getPath("/data"), "kept");

		assertTrue(manager.renameDrive(null, "before", "after"));
		AVector<ACell> renameTombstone=cursor.get().get(Strings.create("before"));
		assertTrue(DLFSNode.isRegularFile(renameTombstone));
		assertNotNull(cursor.get().get(Strings.create("after")));
		assertEquals("kept", Files.readString(manager.getDrive(null, "after").getPath("/data")));

		assertTrue(manager.deleteDrive(null, "after"));
		AVector<ACell> deleteTombstone=cursor.get().get(Strings.create("after"));
		assertTrue(DLFSNode.isRegularFile(deleteTombstone));
		assertTrue(new DLFSDriveManager(cursor).listDrives(null).isEmpty());
	}

	@Test
	public void testDriveDeleteAndRenameDoNotResurrectFromStaleReplica() throws Exception {
		MapLattice<AString, AVector<ACell>> lattice=MapLattice.create(DLFSLattice.INSTANCE);
		RootLatticeCursor<AHashMap<AString, AVector<ACell>>> deleted=Cursors.createLattice(lattice);
		DLFSDriveManager deleteManager=new DLFSDriveManager(deleted);
		assertTrue(deleteManager.createDrive(null,"home"));
		AHashMap<AString,AVector<ACell>> staleHome=deleted.get();
		assertTrue(deleteManager.deleteDrive(null,"home"));
		deleted.merge(staleHome);
		assertNull(deleteManager.getDrive(null,"home"));
		assertTrue(deleteManager.listDrives(null).isEmpty());

		RootLatticeCursor<AHashMap<AString, AVector<ACell>>> renamed=Cursors.createLattice(lattice);
		DLFSDriveManager renameManager=new DLFSDriveManager(renamed);
		assertTrue(renameManager.createDrive(null,"before"));
		Files.writeString(renameManager.getDrive(null,"before").getPath("/data"),"kept");
		AHashMap<AString,AVector<ACell>> staleBefore=renamed.get();
		assertTrue(renameManager.renameDrive(null,"before","after"));
		renamed.merge(staleBefore);
		assertNull(renameManager.getDrive(null,"before"));
		assertEquals("kept",Files.readString(renameManager.getDrive(null,"after").getPath("/data")));
	}

	@Test
	public void testDeletedDriveCanBeRecreatedWithNewerTimestamp() {
		ALatticeCursor<AHashMap<AString, AVector<ACell>>> cursor=createCursor();
		DLFSDriveManager manager=new DLFSDriveManager(cursor);
		cursor.setContext(LatticeContext.create(CVMLong.create(100),null));
		assertTrue(manager.createDrive(null,"home"));
		cursor.setContext(LatticeContext.create(CVMLong.create(200),null));
		assertTrue(manager.deleteDrive(null,"home"));
		CVMLong deletionTime=DLFSNode.getUTime(cursor.get().get(Strings.create("home")));
		cursor.setContext(LatticeContext.create(CVMLong.create(300),null));
		assertTrue(manager.createDrive(null,"home"));
		AVector<ACell> recreated=cursor.get().get(Strings.create("home"));
		assertTrue(DLFSNode.isDirectory(recreated));
		assertEquals(CVMLong.create(200),deletionTime);
		assertEquals(CVMLong.create(300),DLFSNode.getUTime(recreated));
	}

	@Test
	public void testEqualTimestampLocalRegistryMutationRemainsOwn() {
		ALatticeCursor<AHashMap<AString, AVector<ACell>>> cursor=createCursor();
		cursor.setContext(LatticeContext.create(CVMLong.create(100),null));
		DLFSDriveManager manager=new DLFSDriveManager(cursor);
		assertTrue(manager.createDrive(null,"home"));
		assertTrue(manager.deleteDrive(null,"home"));
		AHashMap<AString,AVector<ACell>> deleted=cursor.get();

		assertTrue(manager.createDrive(null,"home"));
		assertEquals(CVMLong.create(100),
			DLFSNode.getUTime(cursor.get().get(Strings.create("home"))));

		cursor.merge(deleted);
		assertNotNull(manager.getDrive(null,"home"),
			"the local recreated drive is own and must win an equal-timestamp stale tombstone");
	}

	@Test
	public void testTombstonesDoNotConsumeLiveDriveLimit() {
		DLFSDrives drives=DLFSDrives.create();
		assertNotNull(drives.createDrive("first",1));
		assertTrue(drives.deleteDrive("first"));
		assertNotNull(drives.createDrive("second",1));
		assertEquals(List.of("second"),drives.driveNames());
	}

	@Test
	public void testDriveMutationUsesExactContextTimestamp() {
		MapLattice<AString, AVector<ACell>> lattice=MapLattice.create(DLFSLattice.INSTANCE);
		AString home=Strings.create("home");
		AHashMap<AString,AVector<ACell>> initial=convex.core.data.Maps.of(
			home,DLFSNode.createDirectory(CVMLong.create(1_031)));
		LatticeContext context=LatticeContext.create(CVMLong.create(1_000),null)
			.withMaxFutureTimestampSkew(30);
		RootLatticeCursor<AHashMap<AString, AVector<ACell>>> cursor=
			Cursors.createLattice(lattice,initial,context);
		DLFSDriveManager manager=new DLFSDriveManager(cursor);

		assertTrue(manager.deleteDrive(null,"home"));
		AVector<ACell> tombstone=cursor.get().get(home);
		assertEquals(CVMLong.create(1_000),DLFSNode.getUTime(tombstone),
			"local mutation must not ratchet against the stored timestamp");
	}

	@Test
	public void testDriveMergeRejectsDistantFutureTombstones() {
		MapLattice<AString, AVector<ACell>> lattice=MapLattice.create(DLFSLattice.INSTANCE);
		AString home=Strings.create("home");
		AString untracked=Strings.create("untracked");
		AHashMap<AString,AVector<ACell>> initial=convex.core.data.Maps.of(
			home,DLFSNode.createDirectory(CVMLong.create(900)));
		LatticeContext context=LatticeContext.create(CVMLong.create(1_000),null)
			.withMaxFutureTimestampSkew(30);
		RootLatticeCursor<AHashMap<AString, AVector<ACell>>> cursor=
			Cursors.createLattice(lattice,initial,context);

		AHashMap<AString,AVector<ACell>> foreign=convex.core.data.Maps.of(
			home,DLFSNode.createEmptyFile(CVMLong.create(1_031)),
			untracked,DLFSNode.createEmptyFile(CVMLong.create(1_031)));
		cursor.merge(foreign);

		assertTrue(DLFSNode.isDirectory(cursor.get().get(home)));
		assertNull(cursor.get().get(untracked));
	}

	@Test
	public void testCursorBackedRegistryDiscoversMergedUntrackedDrive() {
		ALatticeCursor<AHashMap<AString, AVector<ACell>>> cursor=createCursor();
		DLFSDriveManager manager=new DLFSDriveManager(cursor);
		AString remote=Strings.create("remote");

		cursor.merge(convex.core.data.Maps.of(
			remote,DLFSNode.createDirectory(convex.core.data.prim.CVMLong.ZERO)));

		assertEquals(List.of("remote"), manager.listDrives(null));
		assertNotNull(manager.getDrive(null, "remote"));
	}

	@Test
	public void testClosingCachedFileSystemDoesNotChangeRegistryState() throws Exception {
		DLFSDrives drives=DLFSDrives.create();
		DLFSDriveManager manager=new DLFSDriveManager(drives);
		assertTrue(manager.createDrive(null,"home"));
		FileSystem first=manager.getDrive(null,"home");
		AHashMap<AString,AVector<ACell>> registry=drives.cursor().get();

		first.close();
		FileSystem reopened=manager.getDrive(null,"home");

		assertNotSame(first,reopened);
		assertTrue(reopened.isOpen());
		assertEquals(registry,drives.cursor().get());
		assertEquals(List.of("home"),manager.listDrives(null));
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
				new NodeServer<>(lattice,store,NodeConfig.port(-1));
			first.launch();
			try {
				DLFSDriveManager manager=new DLFSDriveManager(DLFSDrives.connect(first.getRootComponent()));
				assertTrue(manager.createDrive(null, "home"));
				Files.writeString(manager.getDrive(null, "home").getPath("/hello.txt"), "after restart");
				manager.sync();
			} finally {
				first.close();
			}

			NodeServer<AHashMap<AString, AVector<ACell>>> restored=
				new NodeServer<>(lattice,store,NodeConfig.port(-1));
			restored.launch();
			try {
				DLFSDriveManager manager=new DLFSDriveManager(DLFSDrives.connect(restored.getRootComponent()));
				assertEquals(List.of("home"), manager.listDrives(null));
				assertEquals("after restart",
					Files.readString(manager.getDrive(null, "home").getPath("/hello.txt")));
			} finally {
				restored.close();
			}
		}
	}
}
