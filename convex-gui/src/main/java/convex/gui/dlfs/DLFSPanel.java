package convex.gui.dlfs;

import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;

import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTree;
import javax.swing.event.TreeExpansionEvent;
import javax.swing.event.TreeWillExpandListener;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.ExpandVetoException;
import javax.swing.tree.TreePath;

import net.miginfocom.swing.MigLayout;

/**
 * A file browser panel for DLFS directories
 */
@SuppressWarnings("serial")
public class DLFSPanel extends JPanel {
	public DLFSPanel(Path root) {
		setLayout(new MigLayout());
		
		DLFileTreeModel model=new DLFileTreeModel(root);
		JTree treeView = new JTree(model);
		// treeView.setBackground(Color.BLACK);
		DLFSRenderer renderer=new DLFSRenderer();
		renderer.setBackgroundNonSelectionColor(getBackground());
		treeView.setCellRenderer(renderer);
		treeView.setShowsRootHandles(true);
		treeView.setExpandsSelectedPaths(true);
		treeView.setEnabled(true);
		
		treeView.addMouseListener(new MouseAdapter() {
		     public void mousePressed(MouseEvent e) {
		         // int selRow = treeView.getRowForLocation(e.getX(), e.getY());
		         TreePath selPath = treeView.getPathForLocation(e.getX(), e.getY());
		         treeView.setSelectionPath(selPath);
		         // System.out.println("Selected: "+selPath);
		         treeView.validate();
		     }
		 });
		
		treeView.addTreeWillExpandListener(new TreeWillExpandListener() {

            @Override
            public void treeWillExpand(TreeExpansionEvent event) throws ExpandVetoException {
                TreePath treePath = event.getPath();
                if (treePath.getLastPathComponent() instanceof Node) {
                    Node node = (Node) treePath.getLastPathComponent();
                    node.loadChildren();
                }
            }

            @Override
            public void treeWillCollapse(TreeExpansionEvent event) throws ExpandVetoException {

            }
        });
		add(treeView,"dock center");
		
		add(new JLabel("Test"),"dock south");

	}
	
	public static class DLFileTreeModel extends DefaultTreeModel {
		protected Path root;

		public DLFileTreeModel(Path root) {
			super(getNode(root));
		}

		static Node getNode(Path path) {
			Node node= new Node(path);
			
			node.loadChildren();
			return node;
		}


	}
	
	Path getNodePath(Object node) {
		return ((Node)node).getFilePath();
	}
	
	public static class Node extends DefaultMutableTreeNode {
		private boolean childrenLoaded = false;
		private boolean isDirectory = false;
		
		public Node(Path path) {
			super(path);
			isDirectory=Files.isDirectory(path);
			if (isDirectory) {
				setAllowsChildren(true);
			}
		}

		public Path getFilePath() {
			return (Path)this.getUserObject();
		}

		public void loadChildren() {
			if (childrenLoaded) return;
			if (!isDirectory) return;
			
			Path p=getFilePath();
			try (DirectoryStream<Path> stream = Files.newDirectoryStream(p)) {
		        for (Path path : stream) {
		        	if (Files.isDirectory(path)) {
		        		this.add(new Node(path));
		        	}
		        }
		    } catch (Exception e) {
		    	System.err.println(e.getMessage());
		    } 
	        childrenLoaded=true;
		}
		
		@Override
		public boolean isLeaf() {
			return !isDirectory;
		}
		
		@Override
		public String toString() {
			Path name=getFilePath().getFileName();
			return (name==null)?"DLFS Root":name.toString();
		}
		
	}

}
