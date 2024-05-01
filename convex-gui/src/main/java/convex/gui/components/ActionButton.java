package convex.gui.components;

import java.awt.event.ActionListener;

import javax.swing.JButton;

import convex.gui.utils.Toolkit;

/**
 * Standard button for actions
 */
@SuppressWarnings("serial")
public class ActionButton extends JButton {

	public ActionButton(String text, int iconCode, ActionListener al) {
		super(text,(iconCode>0)?Toolkit.menuIcon(iconCode):null);
		this.addActionListener(al);
	}
	
	public ActionButton(String text, ActionListener al) {
		super(text);
		this.addActionListener(al);
	}
}
