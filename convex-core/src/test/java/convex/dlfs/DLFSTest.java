package convex.dlfs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.FileSystem;
import java.nio.file.Path;

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
		
		Path foo=fs.getPath("foo");
		Path foobar=fs.getPath("foo/bar");
		Path baz=fs.getPath("baz");
		assertEquals("foo",foo.toString());

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
	
	@Test public void testRelativePath() throws URISyntaxException {
		DLFSProvider provider=DLFS.provider();
		DLFileSystem fs=provider.newFileSystem(new URI("dlfs"),null);

		Path root=fs.getRoot();
		
		Path empty=fs.getEmptyPath();
		assertEquals(0,empty.getNameCount());
		assertFalse(empty.isAbsolute());
		assertEquals(".",empty.toString());
		
		Path d=fs.getPath(".");
		assertEquals(1,d.getNameCount());
		assertFalse(d.isAbsolute());
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

}
