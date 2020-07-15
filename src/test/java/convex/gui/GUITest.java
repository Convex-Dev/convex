package convex.gui;

import org.junit.jupiter.api.Test;

import convex.core.State;
import convex.core.exceptions.InvalidDataException;
import convex.gui.manager.PeerManager;

/**
 * We can't test much of the GUI easily in unit tests, but we can at least test
 * that the GUI component tree is initialised correctly and consistently.
 */
public class GUITest {
	/**
	 * Manager is the root panel of the GUI. Everything else is built in its
	 * constructor.
	 */
	static final PeerManager manager = new PeerManager();

	@Test
	public void testState() throws InvalidDataException {
		State s = PeerManager.getLatestState();
		s.validate();
	}
}
