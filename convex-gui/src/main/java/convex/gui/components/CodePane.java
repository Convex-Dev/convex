package convex.gui.components;

import java.awt.Color;

import javax.swing.JTextPane;

@SuppressWarnings("serial")
public class CodePane extends JTextPane {
	
	public CodePane() {
		RightCopyMenu.addTo(this);

		// stop catching focus movement keys, useful for Ctrl+up and down etc
		setFocusTraversalKeysEnabled(false);

		setBackground(Color.BLACK);
	}

	@Override public boolean getScrollableTracksViewportWidth() {
		return true;
	}
}
