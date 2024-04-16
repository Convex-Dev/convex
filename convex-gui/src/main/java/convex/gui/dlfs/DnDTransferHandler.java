package convex.gui.dlfs;

import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.io.File;
import java.nio.file.Path;
import java.util.List;

import javax.swing.JComponent;
import javax.swing.JTree;
import javax.swing.TransferHandler;
import javax.swing.tree.TreePath;

@SuppressWarnings("serial")
public abstract class DnDTransferHandler extends TransferHandler {

	/**
	 * 
	 */
	private final DLFSPanel dlfsPanel;

	public DnDTransferHandler(DLFSPanel dlfsPanel) {
		this.dlfsPanel = dlfsPanel;

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
		 Path targetDir=getTargetPath();
		 
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
		 System.out.println("Dropping to: "+targetDir);
		 
		 try {
			 files=(List<File>)(tf.getTransferData(DataFlavor.javaFileListFlavor));
			 if ((files==null)||(files.isEmpty())) return false;		 
			 BrowserUtils.copyFiles(this.dlfsPanel, files, targetDir);	 
			 this.dlfsPanel.setSelectedPath(this.dlfsPanel.getSelectedPath());		 
		 } catch (Exception e) {
			 e.printStackTrace();
			 return false;
		 }		 
		 return true;
	 }
	
	protected abstract Path getTargetPath();
}