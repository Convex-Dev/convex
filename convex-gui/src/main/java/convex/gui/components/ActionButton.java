package convex.gui.components;

import java.awt.event.ActionListener;

import javax.swing.JButton;

import convex.gui.utils.Toolkit;

/**
 * Standard button for actions
 */
@SuppressWarnings("serial")
public class ActionButton extends JButton {

	public ActionButton(String text, int iconCode, ActionListener action) {
		super(text,(iconCode>0)?Toolkit.menuIcon(iconCode):null);
		this.addActionListener(action);
	}
	
	public ActionButton(String text, ActionListener action) {
		this(text,0,action);
	}
	
	public ActionButton(int icon, ActionListener action) {
		this(null,icon,action);
	}
}
