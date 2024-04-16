package convex.gui.dlfs;

import java.awt.Color;
import java.awt.Component;
import java.nio.file.Files;
import java.nio.file.Path;

import javax.swing.BorderFactory;
import javax.swing.Icon;
import javax.swing.JTree;
import javax.swing.UIManager;
import javax.swing.filechooser.FileSystemView;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeCellRenderer;

@SuppressWarnings("serial")
public class DLFSRenderer extends DefaultTreeCellRenderer {
	private static final Color SELCOLOUR=new Color(30,70, 100);
	
	public DLFSRenderer() {
		setBorder(BorderFactory.createEmptyBorder(3, 3, 3, 3));
		setOpaque(false);
		setBackgroundSelectionColor(SELCOLOUR);
	}
	
	static FileSystemView fsView=FileSystemView.getFileSystemView();
	
	@Override
	public Component getTreeCellRendererComponent(JTree tree, Object value, boolean selected, boolean expanded,
			boolean leaf, int row, boolean hasFocus) {
		Path path=(Path) ((DefaultMutableTreeNode)value).getUserObject();
		if (selected) {
			setOpaque(true);
			setBackground(this.getBackgroundSelectionColor());
		} else {
			setOpaque(false);
			//this.setForeground(this.getTextNonSelectionColor());
			setBackground(this.getBackgroundNonSelectionColor());
		}
		Path name=path.getFileName();
		setText((name==null)?"DLFS Root":name.toString());
		
		String iconName= "FileView.fileIcon";
		if (Files.isDirectory(path)) {
			iconName=expanded?"FileView.directoryIcon": "FileView.directoryIcon";
		}
		
		Icon icon;
		if (name==null) {
			icon = UIManager.getIcon("FileView.hardDriveIcon"); // root icon
		} else try {
			icon= FileSystemView.getFileSystemView().getSystemIcon( name.toFile() );
		} catch (Exception e) {
			icon = UIManager.getIcon(iconName);
		}
		
		setIcon(icon);
		return this;
	}

}
