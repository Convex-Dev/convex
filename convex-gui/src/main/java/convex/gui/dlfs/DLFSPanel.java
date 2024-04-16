package convex.gui.dlfs;

import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.io.File;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import javax.swing.DefaultListModel;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTree;
import javax.swing.TransferHandler;
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

	protected DLFileSystem fileSystem;
	
	JLabel pathLabel;
	
	JTree directoryTree;
	JList<Path> fileList;
	
	CodeLabel infoLabel=new CodeLabel("READY");

	private DLPath selectedPath;

	
	public DLFSPanel(DLFileSystem dlfs) {
		this.fileSystem=dlfs;
		selectedPath=dlfs.getRoot();
		setLayout(new MigLayout());
		
		directoryTree = new DirectoryTree(dlfs);
		// treeView.setBackground(Color.BLACK);
		directoryTree.setTransferHandler(new DropTransferHandler());
		
		directoryTree.addTreeSelectionListener(e->{
			DirectoryTree.Node node=(DirectoryTree.Node)directoryTree.getLastSelectedPathComponent();
			if (node==null) {
				setSelectedPath(null);
			} else {
				Path p=node.getFilePath();
				setSelectedPath(p);
			}
		});
		
		fileList=new JList<>(new DefaultListModel<Path>());
		fileList.setTransferHandler(new DropTransferHandler());
		JScrollPane listScrollPane=new JScrollPane(fileList);
		
		JSplitPane splitPane=new JSplitPane(JSplitPane.HORIZONTAL_SPLIT,new JScrollPane(directoryTree), listScrollPane);
		add(splitPane,"dock center");
		
		pathLabel=new JLabel("/");
		add(pathLabel,"dock north");

		add(infoLabel,"dock south");
		
		directoryTree.setSelectionPath(directoryTree.getPathForRow(0));
	}
	
	
	private void setSelectedPath(Path newPath) {
		DefaultListModel<Path> model = ((DefaultListModel<Path>)fileList.getModel());

		infoLabel.setText("ROOT HASH: " +fileSystem.getRootHash()+"\n"+
				"NODE HASH: " +fileSystem.getNodeHash((DLPath)newPath)+"\n");
		
		model.removeAllElements();
		if (!(newPath instanceof DLPath)) {
			pathLabel.setText("No path selected");
			return;
		}
		DLPath p=(DLPath)newPath;
		
		pathLabel.setText(p.toString());

		
		try (DirectoryStream<Path> stream = Files.newDirectoryStream(p)) {
	        for (Path path : stream) {
	        	model.addElement(path.getFileName());
	        }
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}


	public class DropTransferHandler extends TransferHandler {

		public DropTransferHandler() {

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
			 Path targetDir=getSelectedPath();
			 
			 DropLocation dropLocation = support.getDropLocation();
			 if (dropLocation instanceof JTree.DropLocation) {
				 JTree.DropLocation dl =(JTree.DropLocation)dropLocation;
			     TreePath treePath = dl.getPath();
			     if (treePath!=null) {
				     DirectoryTree.Node parent =(DirectoryTree.Node)treePath.getLastPathComponent();
					 System.out.println("Parent: "+parent);
					 targetDir=parent.getFilePath();
			     }
			 }
			 
			 try {
				 files=(List<File>)(tf.getTransferData(DataFlavor.javaFileListFlavor));
				 if ((files==null)||(files.isEmpty())) return false;		 
				 BrowserUtils.copyFiles(DLFSPanel.this, files, targetDir);	 
				 setSelectedPath(getSelectedPath());		 
			 } catch (Exception e) {
				 e.printStackTrace();
				 return false;
			 }		 
			 return true;
		 }
	}

	
	public DLPath getSelectedPath() {
		return selectedPath;
	}

}
