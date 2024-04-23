package convex.gui.components;

import javax.swing.JTextArea;

import convex.gui.utils.Toolkit;

/**
 * A simple label for multi-line text / code components
 */
@SuppressWarnings("serial")
public class CodeLabel extends JTextArea {

	public CodeLabel(String text) {
		this.setText(text);
		this.setEditable(false);
		this.setFont(Toolkit.SMALL_MONO_FONT);
	}
}
