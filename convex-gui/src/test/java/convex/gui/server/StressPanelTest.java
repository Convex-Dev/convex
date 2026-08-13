package convex.gui.server;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import convex.gui.GUIFixtureExtension;
import convex.gui.GUITest;

@ExtendWith(GUIFixtureExtension.class)
public class StressPanelTest {

	@Test
	public void testStressPanelConstruction() {
		GUITest.assumeGUI();
		StressPanel panel = new StressPanel(GUITest.CONVEX);
		assertNotNull(panel);
	}
}
