package convex.gui.server;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;

import convex.gui.GUITest;

public class StressPanelTest {

	@Test
	public void testStressPanelConstruction() {
		GUITest.assumeGUI();
		StressPanel panel = new StressPanel(GUITest.CONVEX);
		assertNotNull(panel);
	}
}
