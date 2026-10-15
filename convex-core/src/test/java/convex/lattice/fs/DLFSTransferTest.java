package convex.lattice.fs;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.FileSystemException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import convex.lattice.fs.impl.DLFSLocal;
import convex.core.data.ACell;
import convex.core.data.AVector;
import convex.core.data.RefSoft;
import convex.etch.EtchStore;
import convex.lattice.cursor.Cursors;
import convex.lattice.cursor.Root;
import java.util.function.UnaryOperator;

public class DLFSTransferTest {
	@TempDir Path temporary;

	@Test
	public void testCrossStoreCopySurvivesSourceCloseAndDestinationReopen() throws Exception {
		byte[] bytes=new byte[100000];
		new java.util.Random(729).nextBytes(bytes);
		var sourceFile=temporary.resolve("source.etch").toFile();
		var targetFile=temporary.resolve("target.etch").toFile();
		try (var store=EtchStore.create(sourceFile)) {
			try (var fs=DLFSLocal.create(DLFS.provider(),"source",Cursors.root(DLFSLattice.INSTANCE.zero()),store)) {
				Files.write(fs.getPath("/file"),bytes);
				store.setRootData(fs.getCursor().get());
			}
		}
		try (var heap=DLFS.create()) {
			try (var sourceStore=EtchStore.create(sourceFile); var targetStore=EtchStore.create(targetFile);
					var source=DLFSLocal.create(DLFS.provider(),"source",Cursors.root(sourceStore.<AVector<ACell>>getRootData()),sourceStore);
					var target=DLFSLocal.create(DLFS.provider(),"target",Cursors.root(DLFSLattice.INSTANCE.zero()),targetStore)) {
				var file=source.getPath("/file");
				Files.copy(file,heap.getPath("/file"));
				Files.copy(file,target.getPath("/file"));
				var data=DLFSNode.getData(target.getNode(target.getPath("/file")));
				assertSame(targetStore,assertInstanceOf(RefSoft.class,data.getRef(0)).getStore());
				targetStore.setRootData(target.getCursor().get());
			}
			assertArrayEquals(bytes,Files.readAllBytes(heap.getPath("/file")));
		}
		try (var store=EtchStore.create(targetFile);
				var fs=DLFSLocal.create(DLFS.provider(),"restored",Cursors.root(store.<AVector<ACell>>getRootData()),store)) {
			assertArrayEquals(bytes,Files.readAllBytes(fs.getPath("/file")));
		}
	}

	@Test
	public void testAliasedViewsAndForks() throws Exception {
		var cursor=Cursors.root(DLFSLattice.INSTANCE.zero());
		try (var first=new DLFSLocal(DLFS.provider(),"first",cursor);
				var second=new DLFSLocal(DLFS.provider(),"second",cursor)) {
			Files.writeString(first.getPath("/source"),"value");
			assertTrue(Files.isSameFile(first.getPath("/source"),second.getPath("/source")));
			var before=cursor.get();
			Files.copy(first.getPath("/source"),second.getPath("/source"),StandardCopyOption.REPLACE_EXISTING);
			Files.move(first.getPath("/source"),second.getPath("/source"),StandardCopyOption.REPLACE_EXISTING);
			assertSame(before,cursor.get());
			Files.move(first.getPath("/source"),second.getPath("/target"),StandardCopyOption.ATOMIC_MOVE);
			assertFalse(Files.exists(first.getPath("/source")));
			assertEquals("value",Files.readString(second.getPath("/target")));
			try (var fork=first.fork()) {
				assertFalse(Files.isSameFile(first.getPath("/target"),fork.getPath("/target")));
				assertThrows(AtomicMoveNotSupportedException.class,
					()->Files.move(first.getPath("/target"),fork.getPath("/target"),StandardCopyOption.ATOMIC_MOVE));
			}
		}
	}

	@Test
	public void testSourceEditDuringCrossDriveMoveIsNotDeleted() throws Exception {
		try (var source=DLFS.create()) {
			var src=source.getPath("/source");
			Files.writeString(src,"snapshot");
			var destinationCursor=new Root<AVector<ACell>>(DLFSLattice.INSTANCE.zero()) {
				@Override public AVector<ACell> updateAndGet(UnaryOperator<AVector<ACell>> action) {
					var result=super.updateAndGet(action);
					try { Files.writeString(src,"concurrent edit"); }
					catch (Exception e) { throw new AssertionError(e); }
					return result;
				}
			};
			try (var destination=new DLFSLocal(DLFS.provider(),"destination",destinationCursor)) {
				var dst=destination.getPath("/destination");
				assertThrows(FileSystemException.class,()->Files.move(src,dst));
				assertEquals("concurrent edit",Files.readString(src));
				assertEquals("snapshot",Files.readString(dst));
			}
		}
	}

	@Test
	public void testCrossDriveCopyAndMove() throws Exception {
		try (var source=DLFS.create(); var target=DLFS.create()) {
			var original=source.getPath("/original");
			var copied=target.getPath("/copied");
			Files.writeString(original,"contents");
			Files.copy(original,copied);
			assertEquals("contents",Files.readString(copied));
			assertTrue(Files.exists(original));
			assertSame(DLFSNode.getData(source.getNode(original)),DLFSNode.getData(target.getNode(copied)));
			Files.move(original,target.getPath("/moved"));
			assertFalse(Files.exists(original));
			assertEquals("contents",Files.readString(target.getPath("/moved")));
		}
	}

	@Test
	public void testCrossDriveFailuresPreserveSourceAndTarget() throws Exception {
		try (var source=DLFS.create(); var target=DLFS.create()) {
			var original=source.getPath("/file");
			var destination=target.getPath("/file");
			Files.writeString(original,"source");
			Files.writeString(destination,"target");
			assertThrows(FileAlreadyExistsException.class,()->Files.copy(original,destination));
			assertThrows(FileAlreadyExistsException.class,()->Files.move(original,destination));
			assertThrows(AtomicMoveNotSupportedException.class,
				()->Files.move(original,destination,StandardCopyOption.ATOMIC_MOVE));
			assertEquals("source",Files.readString(original));
			assertEquals("target",Files.readString(destination));
			Files.move(original,destination,StandardCopyOption.REPLACE_EXISTING);
			assertEquals("source",Files.readString(destination));
			assertFalse(Files.exists(original));
		}
	}

	@Test
	public void testCrossDriveDirectoryCopy() throws Exception {
		try (var source=DLFS.create(); var target=DLFS.create()) {
			var directory=Files.createDirectory(source.getPath("/tree"));
			Files.writeString(directory.resolve("child"),"child");
			Files.copy(directory,target.getPath("/shallow"));
			assertTrue(Files.isDirectory(target.getPath("/shallow")));
			assertFalse(Files.exists(target.getPath("/shallow/child")));
			Files.copy(directory,target.getPath("/deep"),DLFSOption.RECURSIVE);
			assertEquals("child",Files.readString(target.getPath("/deep/child")));
			assertThrows(DirectoryNotEmptyException.class,
				()->Files.copy(directory,target.getPath("/deep"),StandardCopyOption.REPLACE_EXISTING));
		}
	}

	@Test
	public void testDiskTransfersInBothDirections() throws Exception {
		try (DLFSLocal fs=DLFS.create()) {
			Path disk=Files.writeString(temporary.resolve("source"),"from disk");
			Path imported=fs.getPath("/imported");
			Files.copy(disk,imported);
			assertEquals("from disk",Files.readString(imported));
			Path exported=temporary.resolve("exported");
			Files.copy(imported,exported);
			assertEquals("from disk",Files.readString(exported));
			Files.move(disk,fs.getPath("/moved-in"));
			assertFalse(Files.exists(disk));
			assertEquals("from disk",Files.readString(fs.getPath("/moved-in")));
			Files.move(imported,temporary.resolve("moved-out"));
			assertFalse(Files.exists(imported));
			assertEquals("from disk",Files.readString(temporary.resolve("moved-out")));
		}
	}
}
