package convex.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.awt.HeadlessException;

import org.junit.jupiter.api.Test;

import convex.api.Convex;
import convex.core.State;
import convex.core.crypto.AKeyPair;
import convex.core.exceptions.InvalidDataException;
import convex.dlfs.DLFileSystem;
import convex.gui.client.ConvexClient;
import convex.gui.dlfs.DLFSBrowser;
import convex.gui.peer.PeerGUI;
import convex.gui.tools.HackerTools;
import convex.gui.utils.Toolkit;
import convex.peer.API;
import convex.peer.Server;

/**
 * We can't test much of the GUI easily in unit tests, but we can at least test
 * that the GUI component tree is initialised correctly and consistently.
 */
public class GUITest {
	
	static Server SERVER;
	static Convex CONVEX;
	static PeerGUI manager = null;
	

	static {
		try {
			Toolkit.init();
			manager=new PeerGUI(3,AKeyPair.generate());
			SERVER=manager.getPrimaryServer();
			CONVEX=Convex.connect(SERVER);
		} catch (HeadlessException e) {
			// we should have null manager
		}
	}
	
	/**
	 * Manager is the root panel of the GUI. A lot of other stuff is built in its
	 * constructor.
	 */

	@Test
	public void testState() throws InvalidDataException {
		assumeTrue(manager!=null);
		State s = manager.getLatestState();
		s.validate();
	}
	
	/**
	 * Simple test that DLFSBrowser can be constructed and functionality is working
	 */
	@Test
	public void testDLFSBrowser() {
		assumeTrue(manager!=null);
		DLFSBrowser browser=new DLFSBrowser();
		DLFileSystem drive=browser.getDrive();
		assertEquals(0,drive.getRoot().getNameCount());
	}
	
	@Test
	public void testHackerTools() {
		assumeTrue(manager!=null);
		HackerTools tools=new HackerTools();
		assertNotNull(tools.tabs);
	}
	
	@Test
	public void testClientTools() {
		assumeTrue(manager!=null);
		ConvexClient client=new ConvexClient(CONVEX);
		assertNotNull(client.tabs);
	}
}
