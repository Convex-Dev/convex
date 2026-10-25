package convex.gui;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import java.awt.EventQueue;
import java.awt.GraphicsEnvironment;

import javax.swing.JFrame;
import javax.swing.JLabel;

import org.junit.jupiter.api.Test;

import convex.gui.utils.Async;

/**
 * Tests that {@link SwingThreadCheck} catches Swing calls off the EDT
 */
public class SwingThreadCheckTest {

	@Test
	public void testDetectsOffThreadChanges() throws Exception {
		assumeFalse(GraphicsEnvironment.isHeadless());
		// Installed by auto-detection, as for every other test
		assertTrue(SwingThreadCheck.isInstalled());

		JLabel label=new JLabel("before");
		JFrame[] frame=new JFrame[1];
		EventQueue.invokeAndWait(()->{
			frame[0]=new JFrame("Thread check");
			frame[0].add(label);
			frame[0].pack();
			frame[0].setVisible(true);
		});
		try {
			SwingThreadCheck.takeViolations();

			// Changing a showing component off the EDT is caught
			Async.background(()->{
				label.setText("changed off the EDT");
				return null;
			}).get();
			assertFalse(SwingThreadCheck.takeViolations().isEmpty());

			// Asking for a repaint is allowed from any thread
			Async.background(()->{
				label.repaint();
				return null;
			}).get();
			assertTrue(SwingThreadCheck.takeViolations().isEmpty());
		} finally {
			EventQueue.invokeAndWait(()->frame[0].dispose());
		}
	}
}
