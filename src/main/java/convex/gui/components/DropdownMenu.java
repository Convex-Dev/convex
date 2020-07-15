package convex.gui.components;

import javax.swing.JButton;
import javax.swing.JPopupMenu;

import convex.gui.manager.Toolkit;

/**
 * A dropdown menu that can be used wherever an embedded menu is needed.
 */
@SuppressWarnings("serial")
public class DropdownMenu extends JButton {

	private JPopupMenu popupMenu;

	public DropdownMenu(JPopupMenu popupMenu) {

		super();
		this.popupMenu = popupMenu;
		this.setBorder(null);
		this.setIcon(Toolkit.COG);
		this.addActionListener(e -> {
			popupMenu.show(this, 0, this.getHeight());
		});
	}

	public JPopupMenu getMenu() {
		return popupMenu;
	}
}
