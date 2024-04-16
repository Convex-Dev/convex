package convex.gui.dlfs;

import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTree;
import javax.swing.TransferHandler;
import javax.swing.border.BevelBorder;
import javax.swing.event.TreeExpansionEvent;
import javax.swing.event.TreeWillExpandListener;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.ExpandVetoException;
import javax.swing.tree.TreePath;

import convex.dlfs.DLFileSystem;
import convex.dlfs.DLPath;
import convex.gui.components.CodeLabel;
import net.miginfocom.swing.MigLayout;

/**
 * A file browser panel for DLFS directories
 */
@SuppressWarnings("serial")
public class DLFSPanel extends JPanel {

	private DLFileSystem fileSystem;
	
	JLabel pathLabel;
	
	JTree treeView;
	JList<Path> fileList;
	
	CodeLabel infoLabel=new CodeLabel("READY");

	
	public DLFSPanel(DLFileSystem dlfs) {
		this.fileSystem=dlfs;
		setLayout(new MigLayout());
		
		DLFileTreeModel model=new DLFileTreeModel(dlfs);
		treeView = new JTree(model);
		// treeView.setBackground(Color.BLACK);
		DLFSRenderer renderer=new DLFSRenderer();
		renderer.setBackgroundNonSelectionColor(getBackground());
		treeView.setCellRenderer(renderer);
		treeView.setShowsRootHandles(true);
		treeView.setTransferHandler(new DropTransferHandler(fileSystem.getRoot()));
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
		
		treeView.addTreeSelectionListener(e->{
			Node node=(Node)treeView.getLastSelectedPathComponent();
					
			if (node==null) {
				updateTreeSelection(null);
			} else {
				Path p=node.getFilePath();
				updateTreeSelection(p);
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
		treeView.setBorder(BorderFactory.createBevelBorder(BevelBorder.LOWERED));

		
		fileList=new JList<>(new DefaultListModel<Path>());
		fileList.setTransferHandler(new DropTransferHandler(fileSystem.getRoot()));
		JScrollPane listScrollPane=new JScrollPane(fileList);
		
		JSplitPane splitPane=new JSplitPane(JSplitPane.HORIZONTAL_SPLIT,new JScrollPane(treeView), listScrollPane);
		add(splitPane,"dock center");
		
		pathLabel=new JLabel("/");
		add(pathLabel,"dock north");

		add(infoLabel,"dock south");
	}
	
	
	private void updateTreeSelection(Path newPath) {
		DefaultListModel<Path> model = ((DefaultListModel<Path>)fileList.getModel());

		model.removeAllElements();
		if (!(newPath instanceof DLPath)) return;
		DLPath p=(DLPath)newPath;
		
		pathLabel.setText(p.toString());
		infoLabel.setText("ROOT HASH: " +((DLPath)p).getFileSystem().getRootHash()+"\n"+
				"NODE HASH: " +((DLPath)p).getFileSystem().getNodeHash(p)+"\n");

		
		try (DirectoryStream<Path> stream = Files.newDirectoryStream(p)) {
	        for (Path path : stream) {
	        	model.addElement(path.getFileName());
	        }
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}


	public static class DLFileTreeModel extends DefaultTreeModel {
		protected Path root;

		public DLFileTreeModel(DLFileSystem filesystem) {
			super(getNode(filesystem.getRoot()));
		}

		static Node getNode(Path path) {
			Node node= new Node(path);
			
			node.loadChildren();
			return node;
		}


	}
	
	public class DropTransferHandler extends TransferHandler {
		
		private Path directory;

		public DropTransferHandler(Path dir) {
			this.directory=dir;
		}
		@Override
		public boolean canImport(TransferSupport support) {
            if (!support.isDrop()) {
                return false;
            }
            return support.isDataFlavorSupported(DataFlavor.javaFileListFlavor);
        }
		
		@Override
	    public int getSourceActions(JComponent c) {
	        //PropertyDescriptor prop = getPropertyDescriptor(c);
	        return COPY;

	    }
		
		 @SuppressWarnings("unchecked")
		public boolean importData(TransferSupport support) {
			 if (!canImport(support)) return false;
			 
			 Transferable tf =support.getTransferable();
			 List<File> files;
			 Node target=null;
			 Path targetDir=directory;
			 
			 DropLocation dropLocation = support.getDropLocation();
			 if (dropLocation instanceof JTree.DropLocation) {
				 JTree.DropLocation dl =(JTree.DropLocation)dropLocation;
			     TreePath treePath = dl.getPath();
			     if (treePath!=null) {
				     Node parent =(Node)treePath.getLastPathComponent();
					 System.out.println("Parent: "+parent);
					 target=parent;
					 targetDir=parent.getFilePath();
			     }
			 }
			 
			 try {
				 files=(List<File>)(tf.getTransferData(DataFlavor.javaFileListFlavor));
				 if ((files==null)||(files.isEmpty())) return false;
				 
				 for (File f:files) {
					 Path targetPath=targetDir.resolve(f.getName());
					 Path p=Files.copy(f.toPath(),targetPath);
					 System.out.println("Copied to: "+p.toString());
					 System.out.println(Files.size(p));
					 
					 Node newNode=new Node(p);
					 if (target!=null) {
						 target.add(newNode);
					 }
				 }
				 
			 } catch (Exception e) {
				 e.printStackTrace();
				 return false;
			 }
			 
			 return true;
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
