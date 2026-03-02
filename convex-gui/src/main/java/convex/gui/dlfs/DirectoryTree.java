package convex.gui.dlfs;

import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;

import javax.swing.BorderFactory;
import javax.swing.JTree;
import javax.swing.border.BevelBorder;
import javax.swing.event.TreeExpansionEvent;
import javax.swing.event.TreeWillExpandListener;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.ExpandVetoException;
import javax.swing.tree.TreePath;

import convex.lattice.fs.DLFileSystem;

@SuppressWarnings("serial")
public class DirectoryTree extends JTree {

	protected DLFileSystem fileSystem;

	public DirectoryTree(DLFileSystem dlfs) {
		this(dlfs, null);
	}

	public DirectoryTree(DLFileSystem dlfs, String driveName) {
		this.fileSystem=dlfs;

		setModel(new DirectoryTree.DLFileTreeModel(dlfs, driveName));
		setExpandsSelectedPaths(true);
		setEnabled(true);
		
		DLFSTreeCellRenderer renderer=new DLFSTreeCellRenderer();

		setCellRenderer(renderer);
		setShowsRootHandles(false);
		setRootVisible(true);
		setBorder(BorderFactory.createBevelBorder(BevelBorder.LOWERED));
 
		addMouseListener(new MouseAdapter() {
		     public void mousePressed(MouseEvent e) {
		         TreePath selPath = getPathForLocation(e.getX(), e.getY());
		         setSelectionPath(selPath);
		         validate();
		     }
		});
		
		addTreeWillExpandListener(new TreeWillExpandListener() {

            @Override
            public void treeWillExpand(TreeExpansionEvent event) throws ExpandVetoException {
                TreePath treePath = event.getPath();
                if (treePath.getLastPathComponent() instanceof DirectoryTree.Node) {
                    DirectoryTree.Node node = (DirectoryTree.Node) treePath.getLastPathComponent();
                    node.loadChildren();
                }
            }

            @Override
            public void treeWillCollapse(TreeExpansionEvent event) throws ExpandVetoException {

            }
        });
	}
	
	public static class DLFileTreeModel extends DefaultTreeModel {
		protected Path root;

		public DLFileTreeModel(DLFileSystem filesystem, String driveName) {
			super(getNode(filesystem.getRoot(), driveName));
		}

		static DirectoryTree.Node getNode(Path path, String driveName) {
			DirectoryTree.Node node= new DirectoryTree.Node(path, driveName);
			node.loadChildren();
			return node;
		}
	}

	/**
	 * Reloads children of the currently selected node (or root) and
	 * notifies the model so the tree display updates.
	 */
	public void refreshSelectedNode() {
		Node node = null;
		TreePath selPath = getSelectionPath();
		if (selPath != null && selPath.getLastPathComponent() instanceof Node) {
			node = (Node) selPath.getLastPathComponent();
		}
		if (node == null) {
			node = (Node) getModel().getRoot();
		}
		if (node == null) return;
		node.loadChildren();
		((DefaultTreeModel) getModel()).nodeStructureChanged(node);
		// Re-select to keep selection visible
		if (selPath != null) setSelectionPath(selPath);
	}

	public static class Node extends DefaultMutableTreeNode {
		private boolean childrenLoaded = false;
		private boolean isDirectory = false;
		private String displayName;

		public Node(Path path) {
			this(path, null);
		}

		public Node(Path path, String displayName) {
			super(path);
			this.displayName = displayName;
			isDirectory=Files.isDirectory(path);
			if (isDirectory) {
				setAllowsChildren(true);
			}
		}

		public Path getFilePath() {
			return (Path)this.getUserObject();
		}

		public void loadChildren() {
			if (childrenLoaded) {
				this.removeAllChildren();
			}
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
			if (!isDirectory) return true;
			if (childrenLoaded) return getChildCount() == 0;
			// Quick check: any subdirectory exists?
			Path p = getFilePath();
			try (DirectoryStream<Path> stream = Files.newDirectoryStream(p)) {
				for (Path path : stream) {
					if (Files.isDirectory(path)) return false;
				}
			} catch (Exception e) {
				// treat as leaf on error
			}
			return true;
		}
		
		@Override
		public String toString() {
			Path name=getFilePath().getFileName();
			if (name == null) {
				return (displayName != null) ? "DLFS Drive: " + displayName : "DLFS Root";
			}
			return name.toString();
		}
		
	}
}
