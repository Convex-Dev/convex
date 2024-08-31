package convex.gui.components;

import java.awt.Font;
import java.awt.event.ActionListener;

import javax.swing.JButton;

import convex.gui.utils.SymbolIcon;
import convex.gui.utils.Toolkit;

/**
 * Standard button for actions
 */
@SuppressWarnings("serial")
public class ActionButton extends JButton {

	public ActionButton(String text, int iconCode, ActionListener action) {
		this(text,iconCode,1.0,action);
	}
	
	public ActionButton(String text, int iconCode, double scale, ActionListener action) {
		super(text,(iconCode>0)?SymbolIcon.get(iconCode,Toolkit.BUTTON_FONT.getSize()*scale):null);
		
		Font font=Toolkit.BUTTON_FONT;
		if (scale!=1.0) {
			font=font.deriveFont((float)scale);
		}
		this.setFont(font);
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
		ActionButton b=new ActionButton(null,iconCode,1.8,action);
		b.setToolTipText(toolTip);
		return b;
	}
}
