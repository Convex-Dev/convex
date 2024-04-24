package convex.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import convex.core.State;
import convex.core.crypto.AKeyPair;
import convex.core.exceptions.InvalidDataException;
import convex.dlfs.DLFileSystem;
import convex.gui.dlfs.DLFSBrowser;
import convex.gui.peer.PeerGUI;
import convex.gui.utils.Toolkit;

/**
 * We can't test much of the GUI easily in unit tests, but we can at least test
 * that the GUI component tree is initialised correctly and consistently.
 */
public class GUITest {
	
	static {
		Toolkit.init();
	}
	
	/**
	 * Manager is the root panel of the GUI. Everything else is built in its
	 * constructor.
	 */
	static final PeerGUI manager = new PeerGUI(3,AKeyPair.generate());

	@Test
	public void testState() throws InvalidDataException {
		State s = manager.getLatestState();
		s.validate();
	}
	
	@Test
	public void testDLFSBrowser() {
		DLFSBrowser browser=new DLFSBrowser();
		DLFileSystem drive=browser.getDrive();
		assertEquals(0,drive.getRoot().getNameCount());
	}
}
