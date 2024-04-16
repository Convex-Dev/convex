package convex.dlfs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
import convex.core.data.AVector;
import convex.core.data.Blob;
import convex.core.data.Blobs;
import convex.core.data.prim.CVMLong;

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
		
		{ // create two files, check timestamp
			setDriveTimes(1000,driveA,driveB);
			AVector<ACell> nodeA=driveA.createFile(driveA.getPath("foo"));
			AVector<ACell> nodeB=driveB.createFile(driveB.getPath("bar"));
			assertEquals(nodeA, nodeB); // should be identical nodes
			assertEquals(1000,DLFSNode.getUTime(nodeA).longValue());
			
			assertEquals(1000,driveB.getFileAttributes(driveB.getPath("bar")).lastModifiedTime().toMillis());
		}
		
		{ // create two directory trees
			setDriveTimes(1001,driveA,driveB);
			Files.createDirectories(driveA.getPath("tree/a"));
			Files.createDirectories(driveB.getPath("tree/b"));
		}
		
		{ // create conflict at same time
			setDriveTimes(1002,driveA,driveB);
			Files.createDirectories(driveA.getPath("conflict"));
			Files.createFile(driveB.getPath("conflict"));
		}
		
		{ // create conflict at same time
			setDriveTimes(1003,driveA);
			Files.createDirectories(driveA.getPath("conflict2"));
			setDriveTimes(1004,driveB);
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
		
		// root timestamp for drive A should be time of merge. Some files may be past that
		assertEquals(1003,driveA.getFileAttributes(driveA.getPath("/")).lastModifiedTime().toMillis());
		assertEquals(1004,driveA.getFileAttributes(driveA.getPath("/conflict2")).lastModifiedTime().toMillis());
		
		setDriveTimes(1005,driveA,driveB);
		driveB.replicate(driveA);
		assertTrue(Files.isRegularFile(driveB.getPath("conflict"))); // should prefer current value at same timestamp
		assertEquals(1005,Files.getLastModifiedTime(driveB.getPath("/")).toMillis()); // something got updated

		// Delete conflicting file, should make a tombstone!
		Files.delete(driveA.getPath("conflict"));
		
		// Replicate both ways, should get same root hash with no conflicts
		driveA.replicate(driveB);
		driveB.replicate(driveA);
		assertEquals(1005,Files.getLastModifiedTime(driveA.getPath("/")).toMillis()); // something got updated
		assertEquals(driveA.getRootHash(),driveB.getRootHash());
	}

	private void setDriveTimes(long time, DLFileSystem... drives) {
		CVMLong utime=CVMLong.create(time);
		for (DLFileSystem d: drives) {
			d.setTimestamp(utime);
		}
	}


}
