package convex.dlfs.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.StandardCopyOption;

import org.junit.jupiter.api.Test;

import convex.core.cvm.Keywords;
import convex.core.data.ABlob;
import convex.core.data.ACell;
import convex.core.data.AVector;
import convex.core.data.AccountKey;
import convex.core.data.Index;
import convex.core.data.Keyword;
import convex.core.data.RefSoft;
import convex.core.data.Strings;
import convex.core.data.prim.CVMLong;
import convex.core.crypto.AKeyPair;
import convex.core.store.MemoryStore;
import convex.etch.EtchStore;
import convex.dlfs.DLFSApplication;
import convex.dlfs.DLFSDrive;
import convex.dlfs.DLFSDriveManager;
import convex.dlfs.DLFSDrives;
import convex.dlfs.DLFSRegion;
import convex.dlfs.DLFSServer;
import convex.lattice.LatticeContext;
import convex.lattice.LatticeHost;
import convex.lattice.cursor.Cursors;
import convex.lattice.fs.DLFS;
import convex.lattice.fs.DLFSNode;
import convex.lattice.fs.DLFileSystem;
import convex.lattice.fs.DLPath;
import convex.lattice.generic.KeyedLattice;
import convex.node.NodeConfig;
import convex.node.NodeServer;

public class DLFSComponentTest {

	@Test
	public void testEmbeddedSubsystemPublishesOneBoundaryAtATime() throws Exception {
		var project=Strings.create("example");
		var projects=Keyword.intern("projects");
		var workspace=KeyedLattice.create(KEY_DOCUMENTS,DLFSRegion.LATTICE);
		var lattice=KeyedLattice.create(projects,convex.lattice.generic.MapLattice.create(workspace));
		var store=new MemoryStore();
		var root=LatticeHost.create(lattice.zero(),store);
		var app=new convex.lattice.ALatticeApplication<Index<Keyword,ACell>>(lattice,root) {};
		var key=AKeyPair.generate();
		var attached=DLFSRegion.connect(app.cursor(),lattice,null,projects,project,KEY_DOCUMENTS);
		var destination=attached.cursor();
		attached.setContext(LatticeContext.create(CVMLong.create(123),key));
		var files=attached.fork();
		var liveDrive=files.drives(key.getAccountKey()).getOrCreateDrive("home");
		var draft=liveDrive.fork();
		Files.writeString(draft.fileSystem().getPath("/draft.txt"),"embedded subsystem");
		assertFalse(Files.exists(liveDrive.fileSystem().getPath("/draft.txt")));
		draft.fileSystem().sync();
		assertEquals("embedded subsystem",Files.readString(liveDrive.fileSystem().getPath("/draft.txt")));
		assertEquals(lattice.zero(),app.cursor().get());
		liveDrive.sync();
		files.cursor().sync();
		assertEquals(lattice.zero(),app.cursor().get(),"child and cursor sync cannot publish their enclosing component");
		files.sync();
		assertNotNull(destination.get());
		assertEquals(lattice.zero(),root.cursor().get());
		app.sync();
		assertEquals(app.cursor().get(),root.cursor().get());
		org.junit.jupiter.api.Assertions.assertNull(store.getRootData());
		root.sync();
		assertEquals(root.cursor().get(),store.getRootData());
		var restored=DLFSRegion.connect(root.cursor(),lattice,store,projects,project,KEY_DOCUMENTS);
		restored.setContext(files.getContext());
		assertEquals("embedded subsystem",Files.readString(restored.drives(key.getAccountKey())
			.drive("home").fileSystem().getPath("/draft.txt")));
	}

	@Test
	public void testFilesystemAdapterUsesExplicitComponentClock() throws Exception {
		var drives=convex.dlfs.DLFSDrives.wrap(Cursors.root(convex.dlfs.DLFSDrives.LATTICE.zero()));
		try (var drive=drives.createDrive("clock")) {
			var fileSystem=(convex.lattice.fs.impl.DLFSLocal)drive.fileSystem();
			var policy=LatticeContext.create(CVMLong.create(123),null);
			fileSystem.setContext(policy);
			assertSame(policy,drive.getContext());
			Path path=fileSystem.getPath("/fixed.txt");
			Files.writeString(path,"fixed clock");
			assertEquals(CVMLong.create(123),DLFSNode.getUTime(fileSystem.getNode((DLPath)path)));
		}
	}

	private static final Keyword KEY_DOCUMENTS=Keyword.intern("documents");
	private static final Keyword KEY_ARCHIVE=Keyword.intern("archive");

	@Test
	public void testSeparateOwnerViewsRecogniseTheSameDrive() throws Exception {
		var key=AKeyPair.generate();
		var host=LatticeHost.open(convex.lattice.Lattice.ROOT.zero(),new MemoryStore());
		host.setContext(LatticeContext.create(null,key));
		var app=DLFSApplication.connect(convex.lattice.Lattice.ROOT,host,Keywords.FS);
		var first=app.drives(key.getAccountKey()).getOrCreateDrive("home");
		var second=app.drives(key.getAccountKey()).getOrCreateDrive("home");
		assertNotSame(first,second);
		assertEquals(java.util.List.of(Keywords.FS,key.getAccountKey(),Strings.create("home")),first.location().path());
		var source=first.fileSystem().getPath("/source");
		Files.writeString(source,"same working drive");
		assertTrue(Files.isSameFile(source,second.fileSystem().getPath("/source")));
		Files.move(source,second.fileSystem().getPath("/target"),StandardCopyOption.ATOMIC_MOVE);
		assertEquals("same working drive",Files.readString(first.fileSystem().getPath("/target")));
		assertFalse(first.fileSystem().isSameDrive(first.fork().fileSystem()));
	}

	@Test
	public void testCompleteNodeHostedServerStack() throws Exception {
		AKeyPair keyPair=AKeyPair.generate();
		try (EtchStore store=EtchStore.createTemp("dlfs-complete-stack");
				NodeServer<Index<Keyword,ACell>> node=
					new NodeServer<>(convex.lattice.Lattice.ROOT,store,NodeConfig.port(-1))) {
			node.setMergeContext(LatticeContext.create(null,keyPair));
			node.launch();

			DLFSApplication<Index<Keyword,ACell>> application=DLFSApplication.connect(convex.lattice.Lattice.ROOT,
				node.getHost(),Keywords.FS);
			DLFSDriveManager drives=new DLFSDriveManager(
				application.drives(keyPair.getAccountKey()),()->{
					application.sync();
					node.getHost().sync();
				});
			assertTrue(drives.createDrive(null,"home"));
			Files.writeString(drives.getDrive(null,"home").getPath("/hello.txt"),"hello");
			application.sync();

			try (DLFSServer server=DLFSServer.create(drives)) {
				server.start(0);
				HttpRequest request=HttpRequest.newBuilder(URI.create(
					"http://localhost:"+server.getPort()+"/dlfs/home/hello.txt")).build();
				HttpResponse<String> response=HttpClient.newHttpClient().send(
					request,HttpResponse.BodyHandlers.ofString());
				assertEquals(200,response.statusCode());
				assertEquals("hello",response.body());
				var update=HttpRequest.newBuilder(request.uri())
					.PUT(HttpRequest.BodyPublishers.ofString("published by service policy")).build();
				var updated=HttpClient.newHttpClient().send(update,HttpResponse.BodyHandlers.ofString());
				assertTrue(updated.statusCode()>=200 && updated.statusCode()<300,updated.body());
				assertEquals(node.getLocalValue(),store.getRootData(),"HTTP completion must run the configured host publication");
				assertEquals("published by service policy",Files.readString(drives.getDrive(null,"home").getPath("/hello.txt")));
			}
		}
	}

	private static final class CompositeDLFSApplication
			extends DLFSApplication<Index<Keyword,ACell>> {

		private final DLFSRegion archive;

		CompositeDLFSApplication(LatticeHost<Index<Keyword,ACell>> host) {
			super(KeyedLattice.create(KEY_DOCUMENTS,DLFSRegion.LATTICE).addLattice(KEY_ARCHIVE,DLFSRegion.LATTICE),host,KEY_DOCUMENTS);
			this.archive=configured(DLFSRegion.connect(cursor,store(),KEY_ARCHIVE));
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
			DLFSApplication<Index<Keyword,ACell>> application=DLFSApplication.connect(lattice,
				server.getHost(),KEY_DOCUMENTS);
			DLFSDrive drive=application.drives(keyPair.getAccountKey()).createDrive("home");
			assertNotNull(drive);

			Files.writeString(drive.fileSystem().getPath("/hello.txt"),"component path");
			application.sync();
			server.getHost().sync();

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
			var host=LatticeHost.open(convex.lattice.Lattice.ROOT.zero(),store);
			host.setContext(LatticeContext.create(null,keyPair));
			DLFSApplication<Index<Keyword,ACell>> application=
				DLFSApplication.connect(convex.lattice.Lattice.ROOT,host,Keywords.FS);
			DLFSDriveManager driveManager=new DLFSDriveManager(
				application.drives(keyPair.getAccountKey()));
			assertNotNull(application.dlfs());
			assertTrue(driveManager.createDrive(null,"home"));
			Files.writeString(driveManager.getDrive(null,"home")
				.getPath("/hello.txt"),"local application");

			application.sync();
			host.sync();
			store.flush();
			assertEquals(application.cursor().get(),store.getRootData());
		}

		try (EtchStore reopened=EtchStore.create(storeFile)) {
			var host=LatticeHost.open(convex.lattice.Lattice.ROOT.zero(),reopened);
			host.setContext(LatticeContext.create(null,keyPair));
			DLFSApplication<Index<Keyword,ACell>> application=
				DLFSApplication.connect(convex.lattice.Lattice.ROOT,host,Keywords.FS);
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
			var host=LatticeHost.open(convex.lattice.Lattice.ROOT.zero(),store);
			host.setContext(LatticeContext.create(null,hostKey));
			DLFSApplication<Index<Keyword,ACell>> application=
				DLFSApplication.connect(convex.lattice.Lattice.ROOT,host,Keywords.FS);
			Index<Keyword,ACell> before=application.cursor().get();

			assertNotSame(application.drives(hostKey.getAccountKey()),
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
		LatticeHost<Index<Keyword,ACell>> root=LatticeHost.create(lattice.zero(),store);
		root.setContext(LatticeContext.create(null,keyPair));
		CompositeDLFSApplication application=new CompositeDLFSApplication(root);

		assertNotNull(application.drives(keyPair.getAccountKey()).createDrive("live"));
		assertNotNull(application.archive.drives(keyPair.getAccountKey()).createDrive("history"));
		application.sync();
		root.sync();

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
			var root=LatticeHost.create(DLFSRegion.LATTICE.zero(),store);
			root.setContext(LatticeContext.create(null,keyPair));
			DLFSRegion region=DLFSRegion.connect(root,DLFSRegion.LATTICE);
			DLFSDrive drive=region.drives(keyPair.getAccountKey()).getOrCreateDrive("large");
			assertTrue(root.cursor().get().get(keyPair.getAccountKey()).checkSignature());
			var before=root.cursor().get();
			var reopened=region.drives(keyPair.getAccountKey()).getOrCreateDrive("large",0);
			assertSame(before,root.cursor().get(),"opening must preserve the signed envelope");
			assertSame(region.getContext(),reopened.getContext());
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
			assertEquals(Files.size(path),Files.size(reopened.fileSystem().getPath("/stream.bin")));
		}
	}

	@Test
	public void testFilesCopyPersistsCursorBackedBlobDataIncrementally() throws Exception {
		long copySize=Long.getLong("convex.dlfs.copyTestBytes",20L*1024*1024);
		if (copySize<=DLFileSystem.BLOB_PERSIST_INTERVAL) {
			throw new IllegalArgumentException("Copy test size must cross the blob persistence interval");
		}
		Path source=Files.createTempFile("dlfs-copy-source",".bin");
		try {
			byte[] megabyte=new byte[1024*1024];
			for (int i=0; i<megabyte.length; i++) megabyte[i]=(byte)i;
			try (var channel=Files.newByteChannel(source,StandardOpenOption.WRITE)) {
				long remaining=copySize;
				while (remaining>0) {
					int count=(int)Math.min(remaining,megabyte.length);
					ByteBuffer buffer=ByteBuffer.wrap(megabyte,0,count);
					while (buffer.hasRemaining()) channel.write(buffer);
					remaining-=count;
				}
			}

			AKeyPair keyPair=AKeyPair.generate();
			try (EtchStore store=EtchStore.createTemp("dlfs-files-copy")) {
				var root=LatticeHost.create(DLFSRegion.LATTICE.zero(),store);
				root.setContext(LatticeContext.create(null,keyPair));
				var drives=DLFSRegion.connect(root,DLFSRegion.LATTICE).drives(keyPair.getAccountKey()).cursor();
				try (var fileSystem=DLFS.connect(drives,Strings.create("large"),store)) {
					Path target=fileSystem.getPath("/copy.bin");

					Files.copy(source,target);

					assertEquals(copySize,Files.size(target));
					AVector<ACell> fileNode=fileSystem.getNode((DLPath)target);
					ABlob data=DLFSNode.getData(fileNode);
					assertInstanceOf(RefSoft.class,data.getRef(0),
						"completed blob branches should be reclaimable through Etch");
				}
			}
		} finally {
			Files.deleteIfExists(source);
		}
	}
}
