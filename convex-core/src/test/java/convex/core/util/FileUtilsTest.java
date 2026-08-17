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
	
	/**
	 * Regression test for #324: a leading "~" followed by a sub-path must expand to
	 * that sub-path directly under the user home directory, with the home path left
	 * intact. An earlier regex-based expansion ate the backslashes of a Windows home
	 * (e.g. {@code C:\Users\Name} -> {@code C:UsersName}), producing a drive-relative
	 * path that resolved against the working directory. String concatenation avoids
	 * that; this test locks it so a regex replace can't creep back in.
	 */
	@Test public void testHomeSubPathExpansion() {
		String home = System.getProperty("user.home");
		File expected = new File(new File(home), ".convex/keystore.pfx");

		File file = FileUtils.getFile("~/.convex/keystore.pfx");
		assertTrue(file.isAbsolute());
		assertEquals(expected.getPath(), file.getPath()); // home intact, no mangling
		assertTrue(file.getPath().startsWith(new File(home).getPath()),
				"expanded path must sit under the home directory: " + file);

		// getPath() resolves identically to getFile()
		Path path = FileUtils.getPath("~/.convex/keystore.pfx");
		assertEquals(expected.getPath(), path.toFile().getPath());
	}

	@Test public void testRelativePathResolvesAgainstWorkingDirectory() {
		Path relative = Path.of("dev", "venue.json");
		Path expected = relative.toAbsolutePath();

		assertEquals(expected, FileUtils.getPath(relative.toString()));
		assertEquals(FileUtils.getFile(relative.toString()).toPath(), FileUtils.getPath(relative.toString()));
	}

	@Test public void testAbsolutePathIsPreserved() {
		Path absolute = TEMP.resolve("config.json").toAbsolutePath();
		assertEquals(absolute, FileUtils.getPath(absolute.toString()));
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
