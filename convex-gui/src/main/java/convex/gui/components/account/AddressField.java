package convex.gui.components.account;

import javax.swing.JTextField;
import javax.swing.text.AttributeSet;
import javax.swing.text.BadLocationException;
import javax.swing.text.Document;
import javax.swing.text.PlainDocument;

import convex.core.cvm.Address;
import convex.core.text.Text;
import convex.gui.utils.Toolkit;

@SuppressWarnings("serial")
public class AddressField extends JTextField {

	public AddressField() {
		super();
		//setFocusLostBehavior(JFormattedTextField.COMMIT_OR_REVERT);
		setFont(Toolkit.MONO_FONT);
		setBorder(null);
	}
	
	@Override 
	protected Document createDefaultModel() {
		return new AddressDocument();
	}
	
	public Address getAddress() {
		return Address.parse(getText());
	}
	
	public class AddressDocument extends PlainDocument {
		@Override
		public void insertString(int offset, String s, AttributeSet a) throws BadLocationException {
			if (s == null) return;
			if (s.isEmpty()) return;

			// Typing '#' at any point resets the field to just '#'
			if ("#".equals(s)) {
				super.remove(0, getLength());
				super.insertString(0, "#", a);
				// Caret after '#'
				AddressField.this.setCaretPosition(1);
				return;
			}

			// Compose the prospective new text
			String current = getText(0, getLength());
			StringBuilder sb = new StringBuilder(current);
			sb.insert(offset, s);
			String preText = sb.toString();

			// If we have any content, ensure it starts with '#'
			String newText = preText;
			boolean addedHash = false;
			if (!newText.isEmpty() && newText.charAt(0) != '#') {
				newText = "#" + newText;
				addedHash = true;
			}

			// Validate: either "#" or "#" followed only by ASCII digits
			boolean ok = false;
			if (newText.equals("#")) {
				ok = true;
			} else if ((newText.length() > 1) && (newText.charAt(0) == '#')) {
				ok = true;
				for (int i = 1; i < newText.length(); i++) {
					if (!Text.isASCIIDigit(newText.charAt(i))) {
						ok = false;
						break;
					}
				}
			}
			if (!ok) return; // reject invalid edits

			// Apply validated text
			super.remove(0, getLength());
			super.insertString(0, newText, a);

			// Place caret where user expects it: after inserted text
			int caretPos = offset + s.length() + (addedHash ? 1 : 0);
			if (caretPos > newText.length()) caretPos = newText.length();
			if (caretPos < 0) caretPos = 0;
			AddressField.this.setCaretPosition(caretPos);
		}
		
		@Override
		public void remove(int offs, int len) throws BadLocationException {
			super.remove(offs, len);
			
			String text = getText(0, getLength());
			
			// Don't allow the field to become completely empty: reset to "#"
			if (text.isEmpty()) {
				super.insertString(0, "#", null);
				AddressField.this.setCaretPosition(1);
				return;
			}
			
			// Ensure first character is always '#'
			if (text.charAt(0) != '#') {
				text = "#" + text;
				super.remove(0, getLength());
				super.insertString(0, text, null);
				// Try to keep caret just after '#'
				int caretPos = 1;
				if (caretPos > text.length()) caretPos = text.length();
				AddressField.this.setCaretPosition(caretPos);
			}
		}
	}
}
