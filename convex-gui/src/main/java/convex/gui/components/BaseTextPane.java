package convex.gui.components;

import java.awt.Color;

import javax.swing.JTextPane;
import javax.swing.text.AttributeSet;
import javax.swing.text.BadLocationException;
import javax.swing.text.Document;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyleContext;

/**
 * Base class for formatted text components
 */
@SuppressWarnings("serial")
public class BaseTextPane extends JTextPane {
	private static final StyleContext sc = StyleContext.getDefaultStyleContext();

	
	public void append(String text, Color c) {
		AttributeSet aset = sc.getEmptySet();
		if (c!=null) {
			aset=sc.addAttribute(SimpleAttributeSet.EMPTY, StyleConstants.Foreground, c);
		}

		Document d=getDocument();
		int len = d.getLength();
		try {
			d.insertString(len, text, aset);
		} catch (BadLocationException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		//pane.setCaretPosition(len);
		//pane.setCharacterAttributes(aset, false);
		//pane.replaceSelection(text);
		repaint();
	}
	
	public void append(String text) {
		append(text,null);
	}
}
