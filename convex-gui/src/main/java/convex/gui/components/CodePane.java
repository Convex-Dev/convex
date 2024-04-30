package convex.gui.components;

import java.awt.Color;

@SuppressWarnings("serial")
public class CodePane extends BaseTextPane {
	
	public CodePane() {
		RightCopyMenu.addTo(this);

		// stop catching focus movement keys, useful for Ctrl+up and down etc
		setFocusTraversalKeysEnabled(false);

		setBackground(Color.BLACK);
	}

	@Override public boolean getScrollableTracksViewportWidth() {
		return true;
	}

	/**
	 * Gets the length of this document
	 * @return
	 */
	public int docLength() {
		return getDocument().getLength();
	}
}
