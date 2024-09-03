package convex.gui.dlfs;

import static org.junit.jupiter.api.Assertions.assertSame;

import java.net.URI;
import java.net.URISyntaxException;

import org.junit.jupiter.api.Test;

import convex.dlfs.DLFS;
import convex.dlfs.DLFSProvider;
import convex.dlfs.DLFileSystem;
import convex.gui.GUITest;

public class DLFSGUITest {

	@Test public void testDLFSPanel() throws URISyntaxException {
		GUITest.assumeGUI();
		
		DLFSProvider provider=DLFS.provider();
		DLFileSystem fs=provider.newFileSystem(new URI("dlfs-test"),null);
		DLFSPanel pan=new DLFSPanel(fs);
		
		assertSame(fs,pan.getFileSystem());
		
	}
	
}
