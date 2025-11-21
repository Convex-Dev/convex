package convex.core.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestInstance.Lifecycle;

import convex.core.data.Blob;
import convex.core.data.Strings;

@TestInstance(Lifecycle.PER_CLASS)
public class FileUtilsTest {

	Path TEMP;
	
	@BeforeAll 
	public void setup() throws IOException {
		TEMP=Files.createTempDirectory("fileTest");
	}
	
	@BeforeAll 
	public void cleanup() throws IOException {
		Files.deleteIfExists(TEMP);
	}
	
	@Test public void testGetHomePath() {
		String home=System.getProperty("user.home");
		assertNotNull(home);
		
		File file=FileUtils.getFile("~");
		assertEquals(home,file.toString());
		assertTrue(file.isAbsolute());
		assertTrue(file.isDirectory());
		
		Path path=FileUtils.getPath("~");
		assertEquals(home,path.toString());
		assertTrue(path.isAbsolute());
		assertTrue(Files.isDirectory(path));
	}
	
	@Test public void testFileOps() throws IOException {
		Path DIR=FileUtils.ensureFilePath(TEMP.resolve("testOps/foo.bar")).getParent();
		assertTrue(Files.exists(DIR));
		assertTrue(Files.isDirectory(DIR));
		try {
			
			
			Path TEXT=DIR.resolve("hello.txt");
			try { // text file
				FileUtils.writeFileAsString(TEXT, "hello");
				String rs=FileUtils.loadFileAsString(DIR.toString()+"/hello.txt");
				assertEquals("hello",rs);
				
				Blob b=FileUtils.loadFileAsBlob(TEXT);
				assertEquals("hello",Strings.create(b).toString());
				
				
			} finally {
				Files.delete(TEXT);
			}
		} finally {
			Files.deleteIfExists(DIR);
		}
	}
}
