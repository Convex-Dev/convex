package convex.gui.components;

import javax.swing.JTextArea;

import convex.gui.manager.Toolkit;

@SuppressWarnings("serial")
public class CodeLabel extends JTextArea {

	public CodeLabel(String text) {
		this.setText(text);
		this.setBackground(null);
		this.setEditable(false);
		this.setFont(Toolkit.SMALL_MONO_FONT);
	}
}
