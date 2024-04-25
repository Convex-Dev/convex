package convex.gui.actor;

import java.awt.Dimension;

import javax.swing.JTextField;

import convex.gui.utils.Toolkit;

@SuppressWarnings("serial")
public class ArgBox extends JTextField {

	public ArgBox() {
		setFont(Toolkit.SMALL_MONO_FONT);
		this.setPreferredSize(new Dimension(300,30));
	}
}
