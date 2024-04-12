package convex.dlfs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.FileSystem;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

public class DLFSTest {
	
	@Test public void testProvider() throws URISyntaxException, IOException {
		DLFSProvider provider=new DLFSProvider();
		
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

}
