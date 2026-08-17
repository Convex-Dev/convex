package convex.dlfs.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import org.junit.jupiter.api.Test;

import convex.core.data.ABlob;
import convex.core.data.ACell;
import convex.core.data.AVector;
import convex.core.data.AccountKey;
import convex.core.data.Index;
import convex.core.data.Keyword;
import convex.core.data.RefSoft;
import convex.core.crypto.AKeyPair;
import convex.core.store.MemoryStore;
import convex.etch.EtchStore;
import convex.dlfs.DLFSApplication;
import convex.dlfs.DLFSDrive;
import convex.dlfs.DLFSDriveManager;
import convex.dlfs.DLFSDrives;
import convex.dlfs.DLFSRegion;
import convex.lattice.LatticeContext;
import convex.lattice.RootComponent;
import convex.lattice.fs.DLFSNode;
import convex.lattice.fs.DLFileSystem;
import convex.lattice.generic.KeyedLattice;
import convex.node.NodeConfig;
import convex.node.NodeServer;

public class DLFSComponentTest {

	private static final Keyword KEY_DOCUMENTS=Keyword.intern("documents");
	private static final Keyword KEY_ARCHIVE=Keyword.intern("archive");

	private static final class CompositeDLFSApplication
			extends DLFSApplication<Index<Keyword,ACell>> {

		private final DLFSRegion archive;

		CompositeDLFSApplication(RootComponent<Index<Keyword,ACell>> host) {
			super(host,KEY_DOCUMENTS);
			this.archive=DLFSRegion.connect(this,KEY_ARCHIVE);
		}
	}

	@Test
	public void testRegionAtArbitraryRootPath() throws Exception {
		KeyedLattice lattice=KeyedLattice.create(KEY_DOCUMENTS,DLFSRegion.LATTICE);
		AKeyPair keyPair=AKeyPair.generate();
		try (EtchStore store=EtchStore.createTemp("dlfs-components");
				NodeServer<Index<Keyword,ACell>> server=new NodeServer<>(lattice,store,NodeConfig.port(-1))) {
			server.setMergeContext(LatticeContext.create(null,keyPair));
			server.launch();
			DLFSApplication<Index<Keyword,ACell>> application=DLFSApplication.connect(
				server.getRootComponent(),KEY_DOCUMENTS);
			DLFSDrive drive=application.drives(keyPair.getAccountKey()).createDrive("home");
			assertNotNull(drive);

			Files.writeString(drive.fileSystem().getPath("/hello.txt"),"component path");
			application.sync();

			assertEquals("component path",Files.readString(drive.fileSystem().getPath("/hello.txt")));
			assertNotNull(server.getCursor().get().get(KEY_DOCUMENTS));
			assertEquals(java.util.List.of("home"),
				application.drives(keyPair.getAccountKey()).driveNames());
			assertEquals(server.getCursor().get(),store.getRootData());
		}
	}

	@Test
	public void testLocalApplicationStackSurvivesRestart() throws Exception {
		AKeyPair keyPair=AKeyPair.generate();
		EtchStore store=EtchStore.createTemp("dlfs-application");
		File storeFile=store.getFile();
		try (store) {
			DLFSApplication<Index<Keyword,ACell>> application=
				DLFSApplication.open(store,keyPair);
			DLFSDriveManager driveManager=new DLFSDriveManager(
				application.drives(keyPair.getAccountKey()));
			assertNotNull(application.dlfs());
			assertTrue(driveManager.createDrive(null,"home"));
			Files.writeString(driveManager.getDrive(null,"home")
				.getPath("/hello.txt"),"local application");

			application.sync();
			application.flush();
			assertEquals(application.cursor().get(),store.getRootData());
		}

		try (EtchStore reopened=EtchStore.create(storeFile)) {
			DLFSApplication<Index<Keyword,ACell>> application=
				DLFSApplication.open(reopened,keyPair);
			DLFSDriveManager driveManager=new DLFSDriveManager(
				application.drives(keyPair.getAccountKey()));
			assertEquals(java.util.List.of("home"),driveManager.listDrives(null));
			assertEquals("local application",Files.readString(
				driveManager.getDrive(null,"home").getPath("/hello.txt")));
		}
	}

	@Test
	public void testApplicationIsNotOwnerScoped() throws Exception {
		AKeyPair hostKey=AKeyPair.generate();
		AccountKey otherOwner=AKeyPair.generate().getAccountKey();
		try (EtchStore store=EtchStore.createTemp("dlfs-multi-owner-application")) {
			DLFSApplication<Index<Keyword,ACell>> application=
				DLFSApplication.open(store,hostKey);
			Index<Keyword,ACell> before=application.cursor().get();

			assertSame(application.drives(hostKey.getAccountKey()),
				application.drives(hostKey.getAccountKey()));
			assertNotSame(application.drives(hostKey.getAccountKey()).cursor(),
				application.drives(otherOwner).cursor());
			assertSame(before,application.cursor().get(),
				"looking up an owner component must not write signed state");
		}
	}

	@Test
	public void testApplicationCanComposeMultipleRegions() throws Exception {
		AKeyPair keyPair=AKeyPair.generate();
		KeyedLattice lattice=KeyedLattice.create(
			KEY_DOCUMENTS,DLFSRegion.LATTICE,
			KEY_ARCHIVE,DLFSRegion.LATTICE);
		MemoryStore store=new MemoryStore();
		RootComponent<Index<Keyword,ACell>> root=RootComponent.create(lattice,store);
		root.cursor().setContext(LatticeContext.create(null,keyPair));
		CompositeDLFSApplication application=new CompositeDLFSApplication(root);

		assertNotNull(application.drives(keyPair.getAccountKey()).createDrive("live"));
		assertNotNull(application.archive.drives(keyPair.getAccountKey()).createDrive("history"));
		application.sync();

		Index<Keyword,ACell> stored=store.getRootData();
		assertNotNull(stored.get(KEY_DOCUMENTS));
		assertNotNull(stored.get(KEY_ARCHIVE));
	}

	@Test
	public void testTemporaryDriveRequiresExplicitSync() throws Exception {
		DLFSDrives drives=DLFSDrives.create();
		DLFSDrive longLived=drives.createDrive("home");
		DLFSDrive temporary=longLived.fork();

		Files.writeString(temporary.fileSystem().getPath("/draft.txt"),"draft");
		assertFalse(Files.exists(longLived.fileSystem().getPath("/draft.txt")));

		temporary.sync();
		assertEquals("draft",Files.readString(longLived.fileSystem().getPath("/draft.txt")));
	}

	@Test
	public void testStreamPersistenceInstallsStoreBackedBlobRefs() throws Exception {
		AKeyPair keyPair=AKeyPair.generate();
		try (EtchStore store=EtchStore.createTemp("dlfs-stream-persist")) {
			var root=RootComponent.create(DLFSRegion.LATTICE,store);
			root.cursor().setContext(LatticeContext.create(null,keyPair));
			DLFSRegion region=DLFSRegion.connect(root);
			DLFSDrive drive=region.drives(keyPair.getAccountKey()).createDrive("large");
			Path path=drive.fileSystem().getPath("/stream.bin");
			byte[] megabyte=new byte[1024*1024];
			for (int i=0; i<megabyte.length; i++) megabyte[i]=(byte)i;

			try (SeekableByteChannel channel=Files.newByteChannel(path,
					StandardOpenOption.CREATE,StandardOpenOption.WRITE)) {
				for (int i=0; i<17; i++) {
					ByteBuffer source=ByteBuffer.wrap(megabyte);
					assertEquals(megabyte.length,channel.write(source));
				}
			}

			assertEquals(17L*megabyte.length,Files.size(path));
			AVector<ACell> fileNode=drive.fileSystem().getNode(
				(convex.lattice.fs.DLPath)path);
			ABlob data=DLFSNode.getData(fileNode);
			assertTrue(data.getRefCount()>0);
			assertInstanceOf(RefSoft.class,data.getRef(0),
				"the completed 16 MiB branch should be reclaimable through Etch");
			assertEquals(megabyte[0],data.byteAt(16L*megabyte.length));
			assertEquals(megabyte[megabyte.length-1],data.byteAt(data.count()-1));
			assertEquals(DLFileSystem.BLOB_PERSIST_INTERVAL,16*megabyte.length);
		}
	}
}
