package convex.gui.dlfs;

import java.awt.Color;
import java.awt.Component;
import java.nio.file.Path;

import javax.swing.BorderFactory;
import javax.swing.Icon;
import javax.swing.JTree;
import javax.swing.filechooser.FileSystemView;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeCellRenderer;

@SuppressWarnings("serial")
public class DLFSTreeCellRenderer extends DefaultTreeCellRenderer {
	private static final Color SELCOLOUR=new Color(30,70, 100);
	private static final Color TEXTCOLOUR=new Color(200,200, 250);
	
	public DLFSTreeCellRenderer() {
		setBorder(BorderFactory.createEmptyBorder(3, 3, 3, 3));
		
		this.setTextNonSelectionColor(TEXTCOLOUR);
		this.setTextSelectionColor(Color.WHITE);
		setOpaque(true);
		// setOpenIcon(Toolkit.);
		//setLeafIcon(Toolkit.CONVEX);
		//setClosedIcon(Toolkit.CONVEX);
	}
	
	static FileSystemView fsView=FileSystemView.getFileSystemView();
	
	@Override
	public Component getTreeCellRendererComponent(JTree tree, Object value, boolean selected, boolean expanded,
			boolean leaf, int row, boolean hasFocus) {
		super.getTreeCellRendererComponent(tree, value, selected, expanded, leaf, row, hasFocus);
		Path path=(Path) ((DefaultMutableTreeNode)value).getUserObject();
		if (selected) {
			setBackground(SELCOLOUR);
		} else {
			//this.setForeground(this.getTextNonSelectionColor());
			setBackground(null);
		}
		Path name=path.getFileName();
		setText((name==null)?"DLFS Root":name.toString());
		
		Icon icon = BrowserUtils.getFileIcon(path);
		
		setIcon(icon);
		
		return this;
	}

}
