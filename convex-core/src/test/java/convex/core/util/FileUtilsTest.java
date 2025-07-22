package convex.core.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;

public class FileUtilsTest {

	
	@Test public void testGetPath() {
		String home=System.getProperty("user.home");
		assertNotNull(home);
		
		assertEquals(home,FileUtils.getFile("~").toString());
		assertEquals(home,FileUtils.getPath("~").toString());
	}
}
