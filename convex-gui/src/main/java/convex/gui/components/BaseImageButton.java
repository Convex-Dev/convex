package convex.gui.components;

import javax.swing.Icon;
import javax.swing.JButton;

@SuppressWarnings("serial")
public class BaseImageButton extends JButton {

	public BaseImageButton(Icon icon) {
		super(icon);
		this.setAlignmentX(JButton.CENTER_ALIGNMENT);
	}
}
