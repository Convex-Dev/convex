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

import convex.dlfs.DLFileSystem;

@SuppressWarnings("serial")
public class DirectoryTree extends JTree {

	protected DLFileSystem fileSystem;

	public DirectoryTree(DLFileSystem dlfs) {
		this.fileSystem=dlfs;
		
		setModel(new DirectoryTree.DLFileTreeModel(dlfs));
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
	
		public DLFileTreeModel(DLFileSystem filesystem) {
			super(getNode(filesystem.getRoot()));
		}
	
		static DirectoryTree.Node getNode(Path path) {
			DirectoryTree.Node node= new DirectoryTree.Node(path);
			node.loadChildren();
			return node;
		}
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
			return !isDirectory;
		}
		
		@Override
		public String toString() {
			Path name=getFilePath().getFileName();
			return (name==null)?"DLFS Root":name.toString();
		}
		
	}
}
