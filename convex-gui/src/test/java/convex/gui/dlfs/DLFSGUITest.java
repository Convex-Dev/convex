package convex.gui.dlfs;

import static org.junit.jupiter.api.Assertions.assertSame;

import java.net.URI;
import java.net.URISyntaxException;

import org.junit.jupiter.api.Test;

import convex.gui.GUITest;
import convex.lattice.fs.DLFS;
import convex.lattice.fs.DLFSProvider;
import convex.lattice.fs.DLFileSystem;

public class DLFSGUITest {

	@Test public void testDLFSPanel() throws URISyntaxException {
		GUITest.assumeGUI();
		
		DLFSProvider provider=DLFS.provider();
		DLFileSystem fs=provider.newFileSystem(new URI("dlfs-test"),null);
		DLFSPanel pan=new DLFSPanel(fs);
		
		assertSame(fs,pan.getFileSystem());
		
	}
	
}
