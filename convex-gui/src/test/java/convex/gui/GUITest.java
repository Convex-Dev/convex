package convex.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

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
	
	Server SERVER;
	Convex CONVEX;
	
	static {
		Toolkit.init();
	}
	
	{
		SERVER=API.launchPeer();
		CONVEX=Convex.connect(SERVER);
	}
	
	/**
	 * Manager is the root panel of the GUI. A lot of other stuff is built in its
	 * constructor.
	 */
	static final PeerGUI manager = new PeerGUI(3,AKeyPair.generate());

	@Test
	public void testState() throws InvalidDataException {
		State s = manager.getLatestState();
		s.validate();
	}
	
	/**
	 * Simple test that DLFSBrowser can be constructed and functionality is working
	 */
	@Test
	public void testDLFSBrowser() {
		DLFSBrowser browser=new DLFSBrowser();
		DLFileSystem drive=browser.getDrive();
		assertEquals(0,drive.getRoot().getNameCount());
	}
	
	@Test
	public void testHackerTools() {
		HackerTools tools=new HackerTools();
		assertNotNull(tools.tabs);
	}
	
	@Test
	public void testClientTools() {
		ConvexClient client=new ConvexClient(CONVEX);
		assertNotNull(client.tabs);
	}
}
