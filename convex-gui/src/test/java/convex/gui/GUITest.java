package convex.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.awt.GraphicsEnvironment;
import java.awt.HeadlessException;

import org.junit.jupiter.api.Test;

import convex.api.Convex;
import convex.core.cvm.State;
import convex.core.crypto.AKeyPair;
import convex.core.exceptions.InvalidDataException;
import convex.dlfs.DLFileSystem;
import convex.gui.dlfs.DLFSBrowser;
import convex.gui.peer.PeerGUI;
import convex.gui.repl.REPLClient;
import convex.gui.tools.HackerTools;
import convex.gui.utils.Toolkit;
import convex.peer.PeerException;
import convex.peer.Server;

/**
 * We can't test much of the GUI easily in unit tests, but we can at least test
 * that the GUI component tree is initialised correctly and consistently.
 */
public class GUITest {
	
	static Server SERVER;
	static Convex CONVEX;
	private static PeerGUI manager = null;
	

	static {
		try {
			Toolkit.init();
			manager=new PeerGUI(3,AKeyPair.generate());
			SERVER=manager.getPrimaryServer();
			CONVEX=Convex.connect(SERVER);
		} catch (HeadlessException e) {
			// ensure null manager
			manager=null;
		} catch (PeerException e) {
			throw new Error(e);
		} 
	}
	
	/**
	 * Test assumption that the GUI can be initialised. If not, we are probably in headless mode, and there is
	 * no point trying to test and GUI components that will just fail with HeadlessException
	 */
	public static void assumeGUI() {
		assumeTrue(manager!=null);
		assumeFalse(GraphicsEnvironment.isHeadless());
	}
	
	/**
	 * Manager is the root panel of the GUI. A lot of other stuff is built in its
	 * constructor.
	 */

	@Test
	public void testState() throws InvalidDataException {
		GUITest.assumeGUI();
		
		State s = manager.getLatestState();
		s.validate();
	}
	
	/**
	 * Simple test that DLFSBrowser can be constructed and functionality is working
	 */
	@Test
	public void testDLFSBrowser() {
		GUITest.assumeGUI();
		
		DLFSBrowser browser=new DLFSBrowser();
		DLFileSystem drive=browser.getDrive();
		assertEquals(0,drive.getRoot().getNameCount());
	}
	
	@Test
	public void testHackerTools() {
		GUITest.assumeGUI();
		
		HackerTools tools=new HackerTools();
		assertNotNull(tools.tabs);
	}
	
	@Test
	public void testClientTools() {
		GUITest.assumeGUI();
		
		REPLClient client=new REPLClient(CONVEX);
		assertNotNull(client.tabs);
	}
}
