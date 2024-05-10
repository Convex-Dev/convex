package convex.gui.components;

import javax.swing.JPopupMenu;

import convex.gui.utils.SymbolIcon;
import convex.gui.utils.Toolkit;

/**
 * A dropdown menu that can be used wherever an embedded menu is needed.
 */
@SuppressWarnings("serial")
public class DropdownMenu extends BaseImageButton {

	private JPopupMenu popupMenu;
	 
	public DropdownMenu(JPopupMenu popupMenu) {
		super(SymbolIcon.get(0xe8b8,Toolkit.ICON_SIZE));
		this.popupMenu = popupMenu;
		// setIconTextGap(0);
		this.addActionListener(e->{
			popupMenu.show(DropdownMenu.this, 0, DropdownMenu.this.getHeight());
		});
	}

	public JPopupMenu getMenu() {
		return popupMenu;
	}
}
