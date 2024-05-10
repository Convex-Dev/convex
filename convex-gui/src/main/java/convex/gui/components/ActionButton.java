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
		this.setFont(Toolkit.BUTTON_FONT);
		this.addActionListener(action);
	}
	
	public ActionButton(String text, ActionListener action) {
		this(text,0,action);
	}
	
	public ActionButton(int icon, ActionListener action) {
		this(null,icon,action);
	}
	
	public static ActionButton build(String text, int iconCode, ActionListener action, String toolTip) {
		ActionButton b=new ActionButton(text,iconCode,action);
		b.setToolTipText(toolTip);
		return b;
	}
	
	public static ActionButton build(int iconCode, ActionListener action, String toolTip) {
		ActionButton b=new ActionButton(iconCode,action);
		b.setToolTipText(toolTip);
		return b;
	}
}
