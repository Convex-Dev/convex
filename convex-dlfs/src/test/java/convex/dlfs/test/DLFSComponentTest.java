package convex.dlfs.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import org.junit.jupiter.api.Test;

import convex.core.data.ABlob;
import convex.core.data.ACell;
import convex.core.data.AVector;
import convex.core.data.Index;
import convex.core.data.Keyword;
import convex.core.data.RefSoft;
import convex.core.crypto.AKeyPair;
import convex.etch.EtchStore;
import convex.dlfs.DLFSDrive;
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

	@Test
	public void testRegionAtArbitraryRootPath() throws Exception {
		KeyedLattice lattice=KeyedLattice.create(KEY_DOCUMENTS,DLFSRegion.LATTICE);
		AKeyPair keyPair=AKeyPair.generate();
		try (EtchStore store=EtchStore.createTemp("dlfs-components");
				NodeServer<Index<Keyword,ACell>> server=new NodeServer<>(lattice,store,NodeConfig.port(-1))) {
			server.setMergeContext(LatticeContext.create(null,keyPair));
			DLFSRegion region=DLFSRegion.connect(server.getRootComponent(),KEY_DOCUMENTS);
			DLFSDrives drives=region.drives(keyPair.getAccountKey());
			DLFSDrive drive=drives.createDrive("home");
			assertNotNull(drive);

			Files.writeString(drive.fileSystem().getPath("/hello.txt"),"component path");

			assertEquals("component path",Files.readString(drive.fileSystem().getPath("/hello.txt")));
			assertNotNull(server.getCursor().get().get(KEY_DOCUMENTS));
			assertEquals(java.util.List.of("home"),drives.driveNames());
		}
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
