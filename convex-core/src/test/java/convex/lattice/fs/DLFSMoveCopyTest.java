package convex.lattice.fs;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.NotDirectoryException;
import java.nio.file.Path;
import java.nio.file.ProviderMismatchException;
import java.nio.file.StandardCopyOption;

import org.junit.jupiter.api.Test;

import convex.core.data.ACell;
import convex.core.data.AVector;
import convex.core.data.Strings;
import convex.core.data.prim.CVMLong;
import convex.lattice.fs.impl.DLFSLocal;

public class DLFSMoveCopyTest {

	@Test
	public void testFileMovePreservesNodeAndUpdatesDirectories() throws IOException {
		DLFSLocal fs=DLFS.create();
		fs.setTimestamp(CVMLong.create(10));
		Path sourceDir=Files.createDirectory(fs.getPath("/source"));
		Path targetDir=Files.createDirectory(fs.getPath("/target"));

		fs.setTimestamp(CVMLong.create(100));
		DLPath source=(DLPath)sourceDir.resolve("file.bin");
		Files.write(source,new byte[] {1,2,3});
		AVector<ACell> withMetadata=DLFSNode.withMetadata(fs.getNode(source),Strings.create("metadata"),CVMLong.create(100));
		fs.updateNode(source,withMetadata);
		AVector<ACell> original=fs.getNode(source);

		fs.setTimestamp(CVMLong.create(200));
		DLPath target=(DLPath)targetDir.resolve("renamed.bin");
		Files.move(source,target,StandardCopyOption.ATOMIC_MOVE);

		assertFalse(Files.exists(source));
		assertSame(original,fs.getNode(target),"move must retain the exact immutable file node");
		assertEquals(CVMLong.create(100),DLFSNode.getUTime(fs.getNode(target)));
		assertEquals(Strings.create("metadata"),DLFSNode.getMetadata(fs.getNode(target)));
		assertEquals(100L,Files.getLastModifiedTime(target).toMillis());
		assertEquals(CVMLong.create(200),DLFSNode.getUTime(fs.getNode((DLPath)sourceDir)));
		assertEquals(CVMLong.create(200),DLFSNode.getUTime(fs.getNode((DLPath)targetDir)));
		assertEquals(CVMLong.create(200),DLFSNode.getUTime(fs.getNode(fs.getRoot())));
		assertEquals(CVMLong.create(200),DLFSNode.getTombstones(fs.getNode((DLPath)sourceDir)).get(Strings.create("file.bin")));
	}

	@Test
	public void testDirectoryMovePreservesTreeAndUTime() throws IOException {
		DLFSLocal fs=DLFS.create();
		fs.setTimestamp(CVMLong.create(100));
		DLPath source=(DLPath)Files.createDirectory(fs.getPath("/tree"));
		Files.write(source.resolve("child.txt"),new byte[] {4,5,6});
		AVector<ACell> original=fs.getNode(source);
		CVMLong originalTime=DLFSNode.getUTime(original);

		fs.setTimestamp(CVMLong.create(200));
		DLPath target=fs.getPath("/renamed-tree");
		Files.move(source,target,DLFSOption.RECURSIVE);

		assertFalse(Files.exists(source));
		assertSame(original,fs.getNode(target),"move must retain the complete immutable directory subtree");
		assertEquals(originalTime,DLFSNode.getUTime(fs.getNode(target)));
		assertArrayEquals(new byte[] {4,5,6},Files.readAllBytes(target.resolve("child.txt")));
		assertEquals(CVMLong.create(200),DLFSNode.getUTime(fs.getNode(fs.getRoot())));
	}

	@Test
	public void testFileCopyGetsNewUTimeAndSharesContent() throws IOException {
		DLFSLocal fs=DLFS.create();
		fs.setTimestamp(CVMLong.create(100));
		DLPath source=(DLPath)Files.write(fs.getPath("/source.bin"),new byte[] {7,8,9});
		AVector<ACell> withMetadata=DLFSNode.withMetadata(fs.getNode(source),Strings.create("metadata"),CVMLong.create(100));
		fs.updateNode(source,withMetadata);
		AVector<ACell> original=fs.getNode(source);

		fs.setTimestamp(CVMLong.create(200));
		DLPath target=fs.getPath("/copy.bin");
		Files.copy(source,target);
		AVector<ACell> copy=fs.getNode(target);

		assertNotSame(original,copy);
		assertSame(DLFSNode.getData(original),DLFSNode.getData(copy),"immutable file data should be structurally shared");
		assertSame(DLFSNode.getMetadata(original),DLFSNode.getMetadata(copy));
		assertEquals(CVMLong.create(100),DLFSNode.getUTime(original));
		assertEquals(CVMLong.create(200),DLFSNode.getUTime(copy));
		assertEquals(200L,Files.getLastModifiedTime(target).toMillis());
	}

	@Test
	public void testDirectoryCopyIsShallowAndNew() throws IOException {
		DLFSLocal fs=DLFS.create();
		fs.setTimestamp(CVMLong.create(100));
		DLPath source=(DLPath)Files.createDirectory(fs.getPath("/source"));
		Files.write(source.resolve("child.txt"),new byte[] {1});
		AVector<ACell> withMetadata=DLFSNode.withMetadata(fs.getNode(source),Strings.create("directory metadata"),CVMLong.create(100));
		fs.updateNode(source,withMetadata);

		fs.setTimestamp(CVMLong.create(200));
		DLPath target=fs.getPath("/copy");
		Files.copy(source,target);

		assertTrue(Files.isDirectory(target));
		assertTrue(DLFSNode.isEmpty(fs.getNode(target)));
		assertFalse(Files.exists(target.resolve("child.txt")));
		assertEquals(Strings.create("directory metadata"),DLFSNode.getMetadata(fs.getNode(target)));
		assertEquals(CVMLong.create(200),DLFSNode.getUTime(fs.getNode(target)));
		assertTrue(Files.exists(source.resolve("child.txt")));
	}

	@Test
	public void testReplaceExistingCopySharesContent() throws IOException {
		DLFSLocal fs=DLFS.create();
		fs.setTimestamp(CVMLong.create(100));
		DLPath source=(DLPath)Files.write(fs.getPath("/source.bin"),new byte[] {7,8,9});
		DLPath target=(DLPath)Files.write(fs.getPath("/target.bin"),new byte[] {1,2,3});
		AVector<ACell> sourceNode=fs.getNode(source);

		fs.setTimestamp(CVMLong.create(200));
		Files.copy(source,target,StandardCopyOption.REPLACE_EXISTING);
		AVector<ACell> copy=fs.getNode(target);

		assertSame(DLFSNode.getData(sourceNode),DLFSNode.getData(copy));
		assertEquals(CVMLong.create(200),DLFSNode.getUTime(copy));
		assertArrayEquals(new byte[] {7,8,9},Files.readAllBytes(target));

		DLPath directory=(DLPath)Files.createDirectory(fs.getPath("/directory"));
		Files.write(directory.resolve("child"),new byte[] {1});
		assertThrows(DirectoryNotEmptyException.class,
			()->Files.copy(source,directory,StandardCopyOption.REPLACE_EXISTING));
	}

	@Test
	public void testRecursiveDirectoryCopySharesCompleteImmutableTree() throws IOException {
		DLFSLocal fs=DLFS.create();
		fs.setTimestamp(CVMLong.create(100));
		DLPath source=(DLPath)Files.createDirectory(fs.getPath("/source"));
		DLPath nested=(DLPath)Files.createDirectory(source.resolve("nested"));
		DLPath sourceFile=(DLPath)Files.write(nested.resolve("file.bin"),new byte[] {1,2,3});
		AVector<ACell> sourceNode=fs.getNode(source);
		AVector<ACell> nestedNode=fs.getNode(nested);
		AVector<ACell> fileNode=fs.getNode(sourceFile);

		fs.setTimestamp(CVMLong.create(200));
		DLPath target=fs.getPath("/copy");
		Files.copy(source,target,DLFSOption.RECURSIVE);
		AVector<ACell> targetNode=fs.getNode(target);

		assertNotSame(sourceNode,targetNode,"the copied root receives its own update time");
		assertSame(DLFSNode.getDirectoryEntries(sourceNode),DLFSNode.getDirectoryEntries(targetNode));
		assertSame(nestedNode,fs.getNode((DLPath)target.resolve("nested")));
		assertSame(fileNode,fs.getNode((DLPath)target.resolve("nested/file.bin")));
		assertEquals(CVMLong.create(100),DLFSNode.getUTime(sourceNode));
		assertEquals(CVMLong.create(200),DLFSNode.getUTime(targetNode));
		assertArrayEquals(new byte[] {1,2,3},Files.readAllBytes(target.resolve("nested/file.bin")));

		fs.setTimestamp(CVMLong.create(300));
		Files.write(target.resolve("nested/file.bin"),new byte[] {9});
		assertArrayEquals(new byte[] {1,2,3},Files.readAllBytes(sourceFile));
		assertArrayEquals(new byte[] {9},Files.readAllBytes(target.resolve("nested/file.bin")));
	}

	@Test
	public void testMoveRejectsInvalidTargets() throws IOException {
		DLFSLocal fs=DLFS.create();
		Files.createDirectory(fs.getPath("/tree"));
		Files.createDirectory(fs.getPath("/tree/child"));
		Files.write(fs.getPath("/source"),new byte[] {1});
		Files.write(fs.getPath("/target"),new byte[] {2});
		Files.write(fs.getPath("/parent-file"),new byte[] {3});

		assertThrows(FileSystemException.class,()->Files.move(fs.getRoot(),fs.getPath("/new-root")));
		assertThrows(FileAlreadyExistsException.class,()->Files.move(fs.getPath("/source"),fs.getRoot()));
		assertThrows(FileAlreadyExistsException.class,()->Files.move(fs.getPath("/source"),fs.getPath("/target")));
		assertThrows(NoSuchFileException.class,()->Files.move(fs.getPath("/missing"),fs.getPath("/new")));
		assertThrows(NoSuchFileException.class,()->Files.move(fs.getPath("/source"),fs.getPath("/missing/new")));
		assertThrows(NotDirectoryException.class,()->Files.move(fs.getPath("/source"),fs.getPath("/parent-file/new")));
		assertThrows(FileSystemException.class,()->Files.move(fs.getPath("/tree"),fs.getPath("/tree/child/moved")));
		assertThrows(UnsupportedOperationException.class,
			()->Files.move(fs.getPath("/source"),fs.getPath("/new"),StandardCopyOption.REPLACE_EXISTING));
	}

	@Test
	public void testCopyRejectsInvalidTargetsAndOptions() throws IOException {
		DLFSLocal fs=DLFS.create();
		Files.write(fs.getPath("/source"),new byte[] {1});
		Files.write(fs.getPath("/target"),new byte[] {2});

		assertThrows(FileSystemException.class,()->Files.copy(fs.getRoot(),fs.getPath("/new-root")));
		assertThrows(FileAlreadyExistsException.class,()->Files.copy(fs.getPath("/source"),fs.getRoot()));
		assertThrows(FileAlreadyExistsException.class,()->Files.copy(fs.getPath("/source"),fs.getPath("/target")));
		assertThrows(NoSuchFileException.class,()->Files.copy(fs.getPath("/missing"),fs.getPath("/new")));
		assertThrows(NoSuchFileException.class,()->Files.copy(fs.getPath("/source"),fs.getPath("/missing/new")));
		assertThrows(UnsupportedOperationException.class,
			()->Files.copy(fs.getPath("/source"),fs.getPath("/new"),StandardCopyOption.COPY_ATTRIBUTES));
	}

	@Test
	public void testSamePathIsNoOpAndCrossDriveRejected() throws IOException {
		DLFSLocal fs=DLFS.create();
		DLPath path=(DLPath)Files.write(fs.getPath("/file"),new byte[] {1});
		AVector<ACell> root=fs.getNode(fs.getRoot());

		Files.move(path,path);
		Files.copy(path,path);
		assertSame(root,fs.getNode(fs.getRoot()));

		DLFSLocal other=DLFS.create();
		assertThrows(ProviderMismatchException.class,()->Files.move(path,other.getPath("/file")));
		assertThrows(ProviderMismatchException.class,()->Files.copy(path,other.getPath("/file")));
	}

	@Test
	public void testMovedSourceTombstoneConvergesAfterReplication() throws IOException {
		DLFSLocal a=DLFS.create();
		DLFSLocal b=DLFS.create();
		a.setTimestamp(CVMLong.create(100));
		DLPath source=(DLPath)Files.write(a.getPath("/source"),new byte[] {1,2,3});
		AVector<ACell> original=a.getNode(source);
		b.replicate(a);

		a.setTimestamp(CVMLong.create(200));
		DLPath target=a.getPath("/target");
		Files.move(source,target);
		b.replicate(a);
		a.replicate(b);

		assertFalse(Files.exists(a.getPath("/source")));
		assertFalse(Files.exists(b.getPath("/source")));
		assertSame(original,a.getNode(target));
		assertEquals(CVMLong.create(100),DLFSNode.getUTime(b.getNode(b.getPath("/target"))));
		assertEquals(CVMLong.create(200),DLFSNode.getTombstones(b.getNode(b.getRoot())).get(Strings.create("source")));
		assertEquals(a.getRootHash(),b.getRootHash());
	}
}
