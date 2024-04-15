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

import org.junit.jupiter.api.Test;

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


}
