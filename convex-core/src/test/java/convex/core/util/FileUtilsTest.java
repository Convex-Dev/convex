package convex.core.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

public class FileUtilsTest {

	
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
}
