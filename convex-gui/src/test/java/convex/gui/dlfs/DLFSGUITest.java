package convex.gui.dlfs;

import static org.junit.jupiter.api.Assertions.assertSame;

import java.net.URI;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import convex.gui.GUIFixtureExtension;
import convex.gui.GUITest;
import convex.lattice.fs.DLFS;
import convex.lattice.fs.DLFSProvider;
import convex.lattice.fs.DLFileSystem;

@ExtendWith(GUIFixtureExtension.class)
public class DLFSGUITest {

	@Test public void testDLFSPanel() throws Exception {
		GUITest.assumeGUI();
		
		DLFSProvider provider=DLFS.provider();
		try (DLFileSystem fs=provider.newFileSystem(new URI("dlfs-test"),null)) {
			DLFSPanel pan=new DLFSPanel(fs);
			try {
				assertSame(fs,pan.getFileSystem());
			} finally {
				pan.close();
			}
		}
		
	}
	
}
