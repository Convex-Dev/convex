package convex.gui.components;

import java.awt.Color;

import javax.swing.JTextPane;
import javax.swing.text.AttributeSet;
import javax.swing.text.BadLocationException;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyleContext;
import javax.swing.text.StyledDocument;

/**
 * Base class for formatted text components
 */
@SuppressWarnings("serial")
public class BaseTextPane extends JTextPane {
	protected static final StyleContext styleContext = StyleContext.getDefaultStyleContext();

	
	public void append(String text, Color c, Integer size) {
		AttributeSet aset = SimpleAttributeSet.EMPTY;
		if (c!=null) {
			aset=styleContext.addAttribute(aset, StyleConstants.Foreground, c);
		}
		if (size!=null) {
			aset=styleContext.addAttribute(aset, StyleConstants.FontSize, size);
		}

		StyledDocument d=getStyledDocument();
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
	
	public void append(String text, Color c) {
		append(text,c,null);
	}
	
	public void append(String text) {
		append(text,null,null);
	}
}
