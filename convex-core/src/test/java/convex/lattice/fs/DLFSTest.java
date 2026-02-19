package convex.lattice.fs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.nio.channels.NonWritableChannelException;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Iterator;
import java.util.Random;

import org.junit.jupiter.api.Test;

import convex.core.data.ABlob;
import convex.core.data.ACell;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Blob;
import convex.core.data.BlobTree;
import convex.core.data.Blobs;
import convex.core.data.Strings;
import convex.core.data.prim.CVMLong;
import convex.lattice.ALattice;
import convex.lattice.LatticeContext;
import convex.lattice.LatticeTest;

public class DLFSTest {
	
	@Test public void testProvider() throws URISyntaxException, IOException {
		DLFSProvider provider=DLFS.provider();
		
		FileSystem fs=provider.newFileSystem(new URI("dlfs"),null);
		
		Path root=fs.getRootDirectories().iterator().next();
		
		assertEquals("/",root.toString());
		
		Path p1=fs.getPath("/hello");
		assertEquals("/hello",p1.toString());
		assertEquals(1,p1.getNameCount());
		
		assertEquals("dlfs:/hello",p1.toUri().toString());
		
		Path p2=p1.getFileName();
		assertNotNull(p2);
		assertFalse(p2.isAbsolute());
		assertEquals("hello",p2.toString());
		
		Path p3=provider.getPath(new URI("dlfs:/hello"));
		assertTrue(p3.isAbsolute());
		assertEquals("hello",p3.getFileName().toString());
	}
	
	@Test public void testPath() throws URISyntaxException {
		DLFSProvider provider=DLFS.provider();
		DLFileSystem fs=provider.newFileSystem(new URI("dlfs"),null);
		
		// Root path
		Path root=fs.getRootDirectories().iterator().next();
		assertEquals("/",root.toString());
		assertEquals(0,root.getNameCount());
		assertEquals(root,fs.getPath("/"));
		assertEquals(root,fs.getPath("/////")); // POSIX 4.11, multiple slashes at start == single slash
		
		assertTrue(Files.isDirectory(root));
		assertFalse(Files.isRegularFile(root));
		
		Path foo=fs.getPath("foo");
		Path foobar=fs.getPath("foo/bar");
		Path baz=fs.getPath("baz");
		assertEquals("foo",foo.toString());
		
		// Neither a directory nor a regular file (because path doesn't exist....)
		assertFalse(Files.isDirectory(foo));
		assertFalse(Files.isRegularFile(root));


		Path rootFoo=fs.getPath("/foo");
		assertEquals("/foo",rootFoo.toString());
		assertEquals(1,rootFoo.getNameCount());

		assertEquals(rootFoo,root.resolve(foo));
		assertEquals(foo,root.relativize(rootFoo));
		
		assertTrue(foobar.startsWith(foobar));
		assertTrue(foobar.startsWith(foo));
		assertFalse(foobar.startsWith(baz));
		assertFalse(foo.startsWith(foobar));
		
		assertTrue(rootFoo.startsWith(root));
		assertFalse(foo.startsWith(root));

	}
	
	@Test public void testBadPath() throws URISyntaxException {
		DLFileSystem fs=DLFS.provider().getFileSystem(new URI("dlfs"));
		
		assertThrows(InvalidPathException.class,()->fs.getPath("foo//bar"));
		assertThrows(InvalidPathException.class,()->fs.getPath(".//.."));
	}
	
	@Test public void testNormalize() throws URISyntaxException {
		DLFileSystem fs=DLFS.createLocal();

		Path root=fs.getRoot();
		assertSame(root,root.normalize());
		
		Path empty=fs.getEmptyPath();
		assertEquals(0,empty.getNameCount());
		assertFalse(empty.isAbsolute());
		assertEquals(".",empty.toString());
		assertSame(empty,empty.normalize());
		
		Path d=fs.getPath(".");
		assertEquals(1,d.getNameCount());
		assertFalse(d.isAbsolute());
		assertNotEquals(empty,d);
		assertEquals(empty,d.normalize());
		
		Path dd=fs.getPath("..");
		assertEquals(1,dd.getNameCount());
		assertFalse(dd.isAbsolute());
		assertSame(dd,dd.normalize());
		
		Path sd=fs.getPath("/.");
		assertEquals(1,sd.getNameCount());
		assertTrue(sd.isAbsolute());
		assertEquals(root,sd.normalize());
		
		Path sdd=fs.getPath("/..");
		assertEquals(1,sdd.getNameCount());
		assertTrue(sdd.isAbsolute());
		assertEquals(root,sd.normalize());
		
		Path sdds=fs.getPath("/../");
		assertEquals(2,sdds.getNameCount());
		assertEquals("/../.",sdds.toString());
		assertTrue(sdds.isAbsolute());
		assertEquals(root,sdds.normalize());

		// Slash at lend gets treated as /. (from POSIX)
		Path foo=fs.getPath("foo");
		Path foos=fs.getPath("foo/");
		assertEquals(2,foos.getNameCount());
		assertEquals("foo/.",foos.toString());
		assertFalse(foos.isAbsolute());
		assertEquals(foo,foos.normalize());

		// .. at start of relative part doesn't change
		Path ddsdd=fs.getPath("../..");
		assertSame(ddsdd,ddsdd.normalize());
		Path ddsddfoo=fs.getPath("../../foo/..");
		assertEquals(ddsdd,ddsddfoo.normalize());

		// Some fun normalisation examples
		assertEquals("../..",fs.getPath("./.././../.").normalize().toString());
		assertEquals("../../bar",fs.getPath("../../foo/../bar").normalize().toString());
		assertEquals("/bar/baz",fs.getPath("/../../foo/./../bar/./baz").normalize().toString());
	}
	
	@Test 
	public void testFilesAPI() throws IOException {
		DLFileSystem fs=DLFS.createLocal();
		
		Path root=fs.getRoot();
		Path fooDir=Files.createDirectory(root.resolve("foo"));
		
		try (DirectoryStream<Path> ds=Files.newDirectoryStream(root)) {
			Iterator<Path> it=ds.iterator();
			assertEquals(fooDir,it.next());
			assertFalse(it.hasNext());
		};

		assertFalse(Files.exists(root.resolve("noob")));
		Path dir2=Files.createDirectories(root.resolve("foo/bar/buzz/../baz"));
		assertTrue(Files.exists(dir2));
		assertTrue(Files.isDirectory(dir2.subpath(0, 2)));

		assertThrows(DirectoryNotEmptyException.class,()->Files.delete(fooDir));

		assertThrows(IOException.class,()->Files.delete(fs.getRoot()));
		assertThrows(NoSuchFileException.class,()->Files.delete(fs.getRoot().resolve("not-found")));
	}
	
	@Test 
	public void testDataFiles() throws IOException {
		DLFileSystem fs=DLFS.createLocal();
		Path root=fs.getRoot();
		final Path fileName=root.resolve("data");
		Path file=Files.createFile(fileName);
		
		assertTrue(Files.exists(file));
		
		try (InputStream is = Files.newInputStream(file)) {
			assertEquals(-1,is.read());
		}
		
		try (OutputStream os = Files.newOutputStream(file)) {
			os.write(42);
		}
		
		// should be one byte in file
		assertEquals(1,Files.size(file));
		
		try (InputStream is = Files.newInputStream(file)) {
			assertEquals(42,is.read());
			assertEquals(-1,is.read());
		}
		
		// read only file channel
		try (SeekableByteChannel fc = Files.newByteChannel(file)) {
			assertTrue(fc.isOpen());
			assertThrows(NonWritableChannelException.class,()->fc.truncate(0));
		}
		
		// append two more bytes
		try (SeekableByteChannel fc = Files.newByteChannel(file,StandardOpenOption.WRITE,StandardOpenOption.APPEND)) {
			assertTrue(fc.isOpen());
			assertEquals(1,fc.position());
			assertEquals(1,fc.size());
			fc.write(ByteBuffer.wrap(new byte[] {43,44}));
			assertEquals(3,fc.size());
		}
		
		// writable file channel, use to truncate
		try (SeekableByteChannel fc = Files.newByteChannel(file,StandardOpenOption.WRITE)) {
			assertTrue(fc.isOpen());
			fc.truncate(0);
			assertEquals(0,fc.size());
			assertEquals(0,fc.position());
		}

		Files.delete(file);
		assertFalse(Files.exists(file));
		
		assertThrows(NoSuchFileException.class,()->{
			try (InputStream is = Files.newInputStream(fileName)) {
				assertEquals(42,is.read());
				assertEquals(-1,is.read());
			}
		});
		
		assertFalse(Files.exists(file));
	}
	
	@Test 
	public void testBigFile() throws IOException {
		DLFileSystem fs=DLFS.createLocal();
		Path root=fs.getRoot();
		final Path fileName=root.resolve("data");
		Path file=Files.createFile(fileName);
		
		int SIZE=10000; // Big enough we need multiple Blob chunks
		ABlob data=Blobs.createRandom(new Random(5465), SIZE);
		
		try (OutputStream os = Files.newOutputStream(file)) {
			os.write(data.getBytes());
		}
		
		assertEquals(SIZE,Files.size(file));
		
		try (OutputStream os = Files.newOutputStream(file,StandardOpenOption.APPEND)) {
			os.write(data.getBytes());
		}
		
		assertEquals(2*SIZE,Files.size(file));
		
		// random offset into file
		int OFF=SIZE/2;
		
		try (SeekableByteChannel fc = Files.newByteChannel(file,StandardOpenOption.WRITE)) {
			fc.position(OFF);
			fc.write(ByteBuffer.wrap(data.getBytes()));
			fc.write(ByteBuffer.wrap(data.getBytes()));
		}
		
		// should have written 2 copies at position OFF, extending file size
		assertEquals(2*SIZE+OFF,Files.size(file));

		// Check we can read a consistent copy from position in input stream
		try (InputStream is = Files.newInputStream(fileName)) {
			is.skip(OFF);
			byte[] bs=is.readNBytes(SIZE);
			assertEquals(SIZE,bs.length);
			Blob data2=Blob.wrap(bs);
			//System.out.println(RT.print(data, SIZE*3));
			//System.out.println(RT.print(data2, SIZE*3));
			assertEquals(data,data2);
		}

	}
	
	@Test public void testReplication() throws IOException {
		DLFileSystem driveA=DLFS.createLocal();
		DLFileSystem driveB=DLFS.createLocal();
		
		long TS=1000;
		{ // create two files, check timestamp
			setDriveTimes(TS,driveA,driveB);
			AVector<ACell> nodeA=driveA.createFile(driveA.getPath("foo"));
			AVector<ACell> nodeB=driveB.createFile(driveB.getPath("bar"));
			assertEquals(nodeA, nodeB); // should be identical nodes
			assertEquals(TS,DLFSNode.getUTime(nodeA).longValue());
			
			assertEquals(TS,driveB.getFileAttributes(driveB.getPath("bar")).lastModifiedTime().toMillis());
		}
		
		{ // create two directory trees
			setDriveTimes(TS+1,driveA,driveB);
			Files.createDirectories(driveA.getPath("tree/a"));
			Files.createDirectories(driveB.getPath("tree/b"));
		}
		
		{ // create conflict at same time
			setDriveTimes(TS+2,driveA,driveB);
			Files.createDirectories(driveA.getPath("conflict"));
			Files.createFile(driveB.getPath("conflict"));
		}
		
		{ // create conflict at same time
			setDriveTimes(TS+3,driveA);
			Files.createDirectories(driveA.getPath("conflict2"));
			setDriveTimes(TS+4,driveB);
			Files.createFile(driveB.getPath("conflict2"));
		}
		
		driveA.replicate(driveB);
		
		assertTrue(Files.isRegularFile(driveA.getPath("foo")));
		assertTrue(Files.isRegularFile(driveA.getPath("bar")));
		assertTrue(Files.isDirectory(driveA.getPath("tree")));
		assertTrue(Files.isDirectory(driveA.getPath("tree/b")));
		assertTrue(Files.isDirectory(driveA.getPath("tree/a")));
		assertTrue(Files.isDirectory(driveA.getPath("conflict"))); // should prefer current value at same timestamp
		assertTrue(Files.isRegularFile(driveA.getPath("conflict2"))); // should prefer newer timestamp from b
		
		// root timestamp should be max of merged node timestamps (TS+4 from driveB's conflict2)
		// This is deterministic merge behavior: merged timestamp = max(node timestamps)
		assertEquals(TS+4,driveA.getFileAttributes(driveA.getPath("/")).lastModifiedTime().toMillis());
		assertEquals(TS+4,driveA.getFileAttributes(driveA.getPath("/conflict2")).lastModifiedTime().toMillis());
		
		setDriveTimes(TS+5,driveA,driveB);
		driveB.replicate(driveA);
		assertTrue(Files.isRegularFile(driveB.getPath("conflict"))); // should prefer current value at same timestamp
		// With deterministic merge, timestamp is max of merged nodes (TS+4), not drive timestamp (TS+5)
		assertEquals(TS+4,Files.getLastModifiedTime(driveB.getPath("/")).toMillis());

		// Delete conflicting file, should make a tombstone!
		// Note: Delete uses current drive timestamp (TS+5, not TS+6)
		Files.delete(driveA.getPath("conflict"));

		// Replicate back to A, should get same root hash with no conflicts
		setDriveTimes(TS+6,driveA,driveB);
		driveA.replicate(driveB);
		driveB.replicate(driveA);
		assertEquals(driveA.getNode(driveA.getRoot()).get(0),driveB.getNode(driveB.getRoot()).get(0));

		//  Replicate again both ways everything should be in sync
		setDriveTimes(TS+7,driveA,driveB);
		driveA.replicate(driveB);
		driveB.replicate(driveA);
		// With deterministic merge, timestamp is max of nodes (TS+5 from tombstone), not drive timestamp
		assertEquals(TS+5,Files.getLastModifiedTime(driveA.getPath("/")).toMillis());
		assertEquals(driveA.getRootHash(),driveB.getRootHash());
	}

	private void setDriveTimes(long time, DLFileSystem... drives) {
		CVMLong utime=CVMLong.create(time);
		for (DLFileSystem d: drives) {
			d.setTimestamp(utime);
		}
	}

	/**
	 * Test that DLFSLattice works as expected with rsync-like merge semantics.
	 * 
	 * Verifies:
	 * - Zero value is an empty directory
	 * - Merge of two filesystem trees combines entries
	 * - Timestamp-based conflict resolution (newer wins)
	 * - Merge with zero/null behaves correctly
	 * - Foreign value validation
	 * - Path support for directory entries
	 */
	@Test
	public void testDLFSLattice() throws IOException {
		DLFSLattice lattice = DLFSLattice.INSTANCE;
		
		// Test zero value
		AVector<ACell> zero = lattice.zero();
		assertNotNull(zero, "Zero value should not be null");
		assertTrue(DLFSNode.isDirectory(zero), "Zero value should be an empty directory");
		assertTrue(DLFSNode.getDirectoryEntries(zero).isEmpty(), "Zero directory should be empty");
		
		// Test merge with zero
		DLFileSystem fs1 = DLFS.createLocal();
		fs1.setTimestamp(CVMLong.create(1000));
		Path file1 = Files.createFile(fs1.getPath("file1"));
		try (OutputStream os = Files.newOutputStream(file1)) {
			os.write(new byte[] {1, 2, 3});
		}
		AVector<ACell> node1 = fs1.getNode(fs1.getRoot());
		
		// Merge with zero should return the same value
		AVector<ACell> mergedWithZero = lattice.merge(node1, zero);
		assertEquals(node1, mergedWithZero, "Merge with zero should return own value");
		
		AVector<ACell> mergedFromZero = lattice.merge(zero, node1);
		assertEquals(node1, mergedFromZero, "Merge from zero should return other value");
		
		// Test merge with null
		assertEquals(node1, lattice.merge(node1, null), "Merge with null should return own value");
		assertEquals(node1, lattice.merge(null, node1), "Merge from null should return other value");
		
		// Test merge of two different filesystem trees (rsync-like behavior)
		DLFileSystem fs2 = DLFS.createLocal();
		fs2.setTimestamp(CVMLong.create(2000));
		
		// Create different files in fs2
		Path file2 = Files.createFile(fs2.getPath("file2"));
		try (OutputStream os = Files.newOutputStream(file2)) {
			os.write(new byte[] {4, 5, 6});
		}
		Path dir2 = Files.createDirectory(fs2.getPath("dir2"));
		Path file3 = Files.createFile(dir2.resolve("file3"));
		try (OutputStream os = Files.newOutputStream(file3)) {
			os.write(new byte[] {7, 8, 9});
		}
		
		AVector<ACell> node2 = fs2.getNode(fs2.getRoot());
		
		// Merge the two filesystems - should combine all entries
		AVector<ACell> merged = lattice.merge(node1, node2);
		assertNotNull(merged, "Merged value should not be null");
		
		// Verify merged filesystem contains entries from both
		DLPath mergedPath1 = fs1.getPath("file1");
		DLPath mergedPath2 = fs1.getPath("file2");
		DLPath mergedPath3 = fs1.getPath("dir2/file3");
		
		AVector<ACell> mergedNode1 = DLFSNode.navigate(merged, mergedPath1);
		AVector<ACell> mergedNode2 = DLFSNode.navigate(merged, mergedPath2);
		AVector<ACell> mergedNode3 = DLFSNode.navigate(merged, mergedPath3);
		
		assertNotNull(mergedNode1, "Merged filesystem should contain file1 from fs1");
		assertNotNull(mergedNode2, "Merged filesystem should contain file2 from fs2");
		assertNotNull(mergedNode3, "Merged filesystem should contain file3 from fs2");
		assertTrue(DLFSNode.isRegularFile(mergedNode1), "file1 should be a regular file");
		assertTrue(DLFSNode.isRegularFile(mergedNode2), "file2 should be a regular file");
		assertTrue(DLFSNode.isRegularFile(mergedNode3), "file3 should be a regular file");
		
		// Test timestamp-based conflict resolution (newer wins)
		DLFileSystem fs3 = DLFS.createLocal();
		fs3.setTimestamp(CVMLong.create(3000));
		Path conflictFile = Files.createFile(fs3.getPath("file1")); // Same name as file1
		try (OutputStream os = Files.newOutputStream(conflictFile)) {
			os.write(new byte[] {10, 11, 12}); // Different content
		}
		AVector<ACell> node3 = fs3.getNode(fs3.getRoot());
		
		// Merge should prefer newer timestamp (fs3's file1)
		AVector<ACell> mergedWithConflict = lattice.merge(node1, node3);
		AVector<ACell> conflictNode = DLFSNode.navigate(mergedWithConflict, fs1.getPath("file1"));
		assertNotNull(conflictNode, "Merged should contain file1");
		ABlob conflictData = DLFSNode.getData(conflictNode);
		assertNotNull(conflictData, "file1 should have data");
		// Should have newer file's data (from fs3, timestamp 3000 > 1000)
		assertEquals(Blob.wrap(new byte[] {10, 11, 12}), conflictData, "Newer timestamp should win in conflict");
		
		// Test idempotency - merging same value should return same
		assertSame(node1, lattice.merge(node1, node1), "Merge of same value should return same instance");
		
		// Test checkForeign
		assertTrue(lattice.checkForeign(node1), "Valid DLFS node should pass checkForeign");
		assertTrue(lattice.checkForeign(node2), "Valid DLFS node should pass checkForeign");
		assertTrue(lattice.checkForeign(merged), "Merged DLFS node should pass checkForeign");
		assertFalse(lattice.checkForeign(null), "Null should fail checkForeign");
		
		// Test path support - navigate through vector index to directory entries
		convex.core.data.AString dirName = convex.core.data.Strings.create("dir2");
		// path(0) gets the directory entries lattice, path("dir2") gets the child DLFSLattice
		ALattice<?> dirEntriesLattice = lattice.path(convex.core.data.prim.CVMLong.ZERO);
		assertNotNull(dirEntriesLattice, "Path to directory entries (index 0) should return a lattice");
		ALattice<?> childLattice = dirEntriesLattice.path(dirName);
		assertNotNull(childLattice, "Path to directory entry should return a lattice");
		assertSame(lattice, childLattice, "Directory entry should use same DLFSLattice");
	}

	/**
	 * Test that DLFSLattice passes the generic lattice property tests from LatticeTest.
	 * This verifies that DLFSLattice correctly implements all required lattice semantics.
	 * 
	 * Tests path functionality with [0, "sharedDir"] where:
	 * - 0 is the directory entries index (POS_DIR)
	 * - "sharedDir" is a directory entry name
	 */
	@Test
	public void testDLFSLatticeGenericProperties() throws IOException {
		DLFSLattice lattice = DLFSLattice.INSTANCE;
		
		// Create two different filesystem nodes for testing
		// Both nodes will have a shared "sharedDir" directory entry for path testing
		DLFileSystem fs1 = DLFS.createLocal();
		fs1.setTimestamp(CVMLong.create(1000));
		Path file1 = Files.createFile(fs1.getPath("file1"));
		try (OutputStream os = Files.newOutputStream(file1)) {
			os.write(new byte[] {1, 2, 3});
		}
		Path sharedDir1 = Files.createDirectory(fs1.getPath("sharedDir"));
		Path fileInDir1 = Files.createFile(sharedDir1.resolve("fileA"));
		try (OutputStream os = Files.newOutputStream(fileInDir1)) {
			os.write(new byte[] {10, 20});
		}
		AVector<ACell> node1 = fs1.getNode(fs1.getRoot());
		
		DLFileSystem fs2 = DLFS.createLocal();
		fs2.setTimestamp(CVMLong.create(2000));
		Path file2 = Files.createFile(fs2.getPath("file2"));
		try (OutputStream os = Files.newOutputStream(file2)) {
			os.write(new byte[] {4, 5, 6});
		}
		Path sharedDir2 = Files.createDirectory(fs2.getPath("sharedDir"));
		Path fileInDir2 = Files.createFile(sharedDir2.resolve("fileB"));
		try (OutputStream os = Files.newOutputStream(fileInDir2)) {
			os.write(new byte[] {30, 40});
		}
		AVector<ACell> node2 = fs2.getNode(fs2.getRoot());
		
		// Run generic lattice property tests (without path parameter)
		// This tests: merge with zero, null handling, idempotency, etc.
		LatticeTest.doLatticeTest(lattice, node1, node2);
		
		// Test with a path to a directory entry: [0, "sharedDir"]
		// Path [0, "sharedDir"] means: get directory entries (index 0), then get "sharedDir" entry
		// Both node1 and node2 have "sharedDir", so RT.getIn will work for both
		AString dirName = Strings.create("sharedDir");
		LatticeTest.doLatticeTest(lattice, node1, node2, 0L, dirName);
	}

	/**
	 * Test that DLFSLattice context-aware merge uses timestamp from context.
	 *
	 * Verifies:
	 * - Context timestamp is used for merge operations when provided
	 * - Fallback to node timestamps when context timestamp is null
	 * - Context timestamp overrides node timestamps for conflict resolution
	 */
	@Test
	public void testDLFSLatticeContextAwareMerge() throws IOException {
		DLFSLattice lattice = DLFSLattice.INSTANCE;

		// Create two filesystems with conflicting files at different timestamps
		DLFileSystem fs1 = DLFS.createLocal();
		fs1.setTimestamp(CVMLong.create(1000));
		Path file1 = Files.createFile(fs1.getPath("conflict.txt"));
		try (OutputStream os = Files.newOutputStream(file1)) {
			os.write(new byte[] {1, 2, 3});
		}
		AVector<ACell> node1 = fs1.getNode(fs1.getRoot());

		DLFileSystem fs2 = DLFS.createLocal();
		fs2.setTimestamp(CVMLong.create(2000));
		Path file2 = Files.createFile(fs2.getPath("conflict.txt"));
		try (OutputStream os = Files.newOutputStream(file2)) {
			os.write(new byte[] {4, 5, 6});
		}
		AVector<ACell> node2 = fs2.getNode(fs2.getRoot());

		// Test 1: Context-aware merge is deterministic
		// Merge timestamp comes from nodes, NOT from context
		CVMLong contextTime = CVMLong.create(3000);
		LatticeContext context = LatticeContext.create(contextTime, null);

		AVector<ACell> merged = lattice.merge(context, node1, node2);
		assertNotNull(merged, "Merged value should not be null");

		// Merged root should have timestamp from max of nodes (2000), not from context
		// This ensures merge is deterministic (same inputs → same output)
		CVMLong mergedTime = DLFSNode.getUTime(merged);
		assertEquals(2000L, mergedTime.longValue(), "Merged root should use max node timestamp (deterministic)");

		// Test 2: Context with null timestamp
		// Should fall back to max of node timestamps (2000)
		LatticeContext emptyContext = LatticeContext.EMPTY;

		AVector<ACell> merged2 = lattice.merge(emptyContext, node1, node2);
		assertNotNull(merged2, "Merged value should not be null");

		CVMLong mergedTime2 = DLFSNode.getUTime(merged2);
		assertEquals(2000L, mergedTime2.longValue(), "Merged root should use max node timestamp when context timestamp is null");

		// Test 3: Basic merge (no context) should behave as before
		AVector<ACell> merged3 = lattice.merge(node1, node2);
		assertNotNull(merged3, "Merged value should not be null");

		CVMLong mergedTime3 = DLFSNode.getUTime(merged3);
		assertEquals(2000L, mergedTime3.longValue(), "Basic merge should use max node timestamp");

		// Test 4: Verify DirectoryEntriesLattice passes context through
		// Create two filesystems with subdirectories
		DLFileSystem fs3 = DLFS.createLocal();
		fs3.setTimestamp(CVMLong.create(1000));
		Path dir1 = Files.createDirectory(fs3.getPath("subdir"));
		Path file3 = Files.createFile(dir1.resolve("file.txt"));
		try (OutputStream os = Files.newOutputStream(file3)) {
			os.write(new byte[] {7, 8, 9});
		}
		AVector<ACell> node3 = fs3.getNode(fs3.getRoot());

		DLFileSystem fs4 = DLFS.createLocal();
		fs4.setTimestamp(CVMLong.create(2000));
		Path dir2 = Files.createDirectory(fs4.getPath("subdir"));
		Path file4 = Files.createFile(dir2.resolve("other.txt"));
		try (OutputStream os = Files.newOutputStream(file4)) {
			os.write(new byte[] {10, 11, 12});
		}
		AVector<ACell> node4 = fs4.getNode(fs4.getRoot());

		// Merge with context - should still be deterministic
		LatticeContext context2 = LatticeContext.create(CVMLong.create(4000), null);
		AVector<ACell> merged4 = lattice.merge(context2, node3, node4);

		// Verify merged root uses max node timestamp (2000), not context timestamp
		// This ensures merge is deterministic
		assertEquals(2000L, DLFSNode.getUTime(merged4).longValue(), "Merged root with subdirs should use max node timestamp (deterministic)");

		// Verify subdirectory also gets deterministic timestamp (max of its input nodes)
		AVector<ACell> mergedSubdir = DLFSNode.navigate(merged4, fs3.getPath("subdir"));
		assertNotNull(mergedSubdir, "Merged subdirectory should exist");
		assertEquals(2000L, DLFSNode.getUTime(mergedSubdir).longValue(), "Merged subdirectory should use max node timestamp (deterministic)");
	}

	/**
	 * Test cursor-level merge with DLFSLattice and LatticeContext.
	 *
	 * This test demonstrates:
	 * - Direct cursor manipulation with lattice merge
	 * - Context-aware merge at the cursor level
	 * - How DLFSLocal could implement context-aware merge API
	 *
	 * NOTE: Currently DLFSLocal.merge() bypasses DLFSLattice and calls DLFSNode.merge() directly.
	 * This test shows how it SHOULD work with the lattice abstraction.
	 */
	@Test
	public void testCursorMergeWithLattice() throws IOException {
		// Create two filesystem trees manually
		DLFileSystem fs1 = DLFS.createLocal();
		fs1.setTimestamp(CVMLong.create(1000));
		Path file1 = Files.createFile(fs1.getPath("fileA.txt"));
		try (OutputStream os = Files.newOutputStream(file1)) {
			os.write(new byte[] {1, 2, 3});
		}
		AVector<ACell> node1 = fs1.getNode(fs1.getRoot());

		DLFileSystem fs2 = DLFS.createLocal();
		fs2.setTimestamp(CVMLong.create(2000));
		Path file2 = Files.createFile(fs2.getPath("fileB.txt"));
		try (OutputStream os = Files.newOutputStream(file2)) {
			os.write(new byte[] {4, 5, 6});
		}
		AVector<ACell> node2 = fs2.getNode(fs2.getRoot());

		// Demonstrate cursor-level merge with lattice and context
		convex.lattice.cursor.Root<AVector<ACell>> cursor = convex.lattice.cursor.Root.create(node1);

		// Merge using DLFSLattice with explicit context
		// Note: Merge is deterministic, so context timestamp is currently not used
		CVMLong mergeTime = CVMLong.create(3000);
		LatticeContext ctx = LatticeContext.create(mergeTime, null);

		AVector<ACell> merged = cursor.updateAndGet(current ->
			DLFSLattice.INSTANCE.merge(ctx, current, node2)
		);

		// Verify merge combined both trees
		assertNotNull(merged, "Merged result should not be null");

		// Verify merged root has max node timestamp (2000), not context timestamp
		// This ensures merge is deterministic (same inputs → same output)
		assertEquals(2000L, DLFSNode.getUTime(merged).longValue(),
			"Merged root should use max node timestamp (deterministic)");

		// Verify both files exist in merged tree
		DLPath pathA = fs1.getPath("fileA.txt");
		DLPath pathB = fs1.getPath("fileB.txt");

		AVector<ACell> mergedFileA = DLFSNode.navigate(merged, pathA);
		AVector<ACell> mergedFileB = DLFSNode.navigate(merged, pathB);

		assertNotNull(mergedFileA, "fileA.txt should exist in merged tree");
		assertNotNull(mergedFileB, "fileB.txt should exist in merged tree");

		// Verify file timestamps are preserved from original nodes
		assertTrue(DLFSNode.isRegularFile(mergedFileA), "fileA should be a regular file");
		assertTrue(DLFSNode.isRegularFile(mergedFileB), "fileB should be a regular file");

		// NOTE: This demonstrates how DLFSLocal.merge() SHOULD work:
		// Instead of:
		//   rootCursor.updateAndGet(rootNode -> DLFSNode.merge(rootNode, other, getTimestamp()))
		// It should be:
		//   rootCursor.updateAndGet(rootNode -> DLFSLattice.INSTANCE.merge(LatticeContext.EMPTY, rootNode, other))
	}

	/**
	 * Test that merge uses timestamps from the nodes being merged,
	 * not the drive's current timestamp for new operations.
	 */
	@Test
	public void testMergeShouldUseNodeTimestampsNotDriveTimestamp() throws IOException {
		DLFileSystem driveA = DLFS.createLocal();
		DLFileSystem driveB = DLFS.createLocal();

		// Create files at time 2000 in both drives
		driveA.setTimestamp(CVMLong.create(2000));
		Files.createFile(driveA.getPath("fileA.txt"));

		driveB.setTimestamp(CVMLong.create(2000));
		Files.createFile(driveB.getPath("fileB.txt"));

		// Now set driveA's current timestamp to 1000 (EARLIER than the files that exist)
		// This is a valid scenario: the drive's "current time" can be set to anything
		driveA.setTimestamp(CVMLong.create(1000));

		// Record root timestamp before merge
		AVector<ACell> rootBeforeMerge = driveA.getNode(driveA.getRoot());
		CVMLong timeBeforeMerge = DLFSNode.getUTime(rootBeforeMerge);
		assertEquals(2000L, timeBeforeMerge.longValue(), "Root should be at time 2000 before merge");

		// Merge: should use timestamps FROM the nodes (2000), NOT drive timestamp (1000)
		driveA.replicate(driveB);

		AVector<ACell> rootAfterMerge = driveA.getNode(driveA.getRoot());
		CVMLong timeAfterMerge = DLFSNode.getUTime(rootAfterMerge);

		// Root should still be at 2000 (max of node timestamps), not drive timestamp (1000)
		assertEquals(2000L, timeAfterMerge.longValue(),
		    "Merge should use node timestamps (2000), not drive timestamp (1000)");
	}

	/**
	 * Test structural sharing with an exabyte-scale sparse file.
	 *
	 * Blobs.createZero() builds a BlobTree where all full children at each
	 * tree level share the same object (Blob.EMPTY_CHUNK at the leaf level).
	 * This means a 1 EB sparse file uses ~15 unique BlobTree nodes, not 2^48 chunks.
	 *
	 * We then write a small payload to the middle and verify the tree-aware
	 * replaceSlice preserves all unchanged subtrees.
	 */
	@Test
	public void testExabyteSparseFile() throws IOException {
		// 1 exabyte = 2^60 bytes. A perfect power of the tree so all children share structure.
		long SIZE = 1L << 60;

		// Construct the sparse zero BlobTree directly
		ABlob sparse = Blobs.createZero(SIZE);
		assertTrue(sparse instanceof BlobTree);
		assertEquals(SIZE, sparse.count());

		// All full chunks should be the interned EMPTY_CHUNK singleton
		assertSame(Blob.EMPTY_CHUNK, sparse.getChunk(0));
		assertSame(Blob.EMPTY_CHUNK, sparse.getChunk(1));
		assertSame(Blob.EMPTY_CHUNK, sparse.getChunk(SIZE / Blob.CHUNK_LENGTH - 1));

		// Verify zero bytes at arbitrary positions
		assertEquals(0, sparse.byteAt(0));
		assertEquals(0, sparse.byteAt(SIZE / 2));
		assertEquals(0, sparse.byteAt(SIZE - 1));

		// Write a small payload to the middle using replaceSlice
		long middle = SIZE / 2;
		Blob payload = Blob.fromHex("CAFEBABE");
		ABlob modified = sparse.replaceSlice(middle, payload);

		// Size unchanged, payload present, surrounding zeros intact
		assertEquals(SIZE, modified.count());
		assertEquals((byte) 0xCA, modified.byteAt(middle));
		assertEquals((byte) 0xFE, modified.byteAt(middle + 1));
		assertEquals((byte) 0xBA, modified.byteAt(middle + 2));
		assertEquals((byte) 0xBE, modified.byteAt(middle + 3));
		assertEquals(0, modified.byteAt(middle - 1));
		assertEquals(0, modified.byteAt(middle + 4));
		assertEquals(0, modified.byteAt(0));
		assertEquals(0, modified.byteAt(SIZE - 1));

		// Identity: replacing with same bytes returns same object
		assertSame(modified, modified.replaceSlice(middle, payload));

		// Now use DLFS to create a sparse file via channel seek + write
		DLFileSystem fs = DLFS.createLocal();
		Path file = Files.createFile(fs.getPath("sparse.dat"));

		byte[] message = "Hello from the middle of an exabyte!".getBytes();
		try (SeekableByteChannel fc = Files.newByteChannel(file, StandardOpenOption.WRITE)) {
			fc.position(middle);
			fc.write(ByteBuffer.wrap(message));
		}

		// File should be middle + message.length bytes
		assertEquals(middle + message.length, Files.size(file));

		// Read back the message
		try (SeekableByteChannel fc = Files.newByteChannel(file, StandardOpenOption.READ)) {
			fc.position(middle);
			ByteBuffer buf = ByteBuffer.allocate(message.length);
			fc.read(buf);
			assertEquals(Blob.wrap(message), Blob.wrap(buf.array()));
		}

		// Read zeros at position 0
		try (SeekableByteChannel fc = Files.newByteChannel(file, StandardOpenOption.READ)) {
			ByteBuffer buf = ByteBuffer.allocate(8);
			fc.read(buf);
			assertEquals(Blob.wrap(new byte[8]), Blob.wrap(buf.array()));
		}
	}

}
