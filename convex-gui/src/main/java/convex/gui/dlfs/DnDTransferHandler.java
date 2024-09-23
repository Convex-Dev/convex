package convex.gui.dlfs;

import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;
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
	 * Parent, probably should be arbitrary component?
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
		return COPY|MOVE;
	}

	@SuppressWarnings("unchecked")
	public boolean importData(TransferSupport support) {
		if (!canImport(support))
			return false;

		Transferable tf = support.getTransferable();
		List<File> files;
		Path targetDir = getTargetDirectory(support);
		System.out.println("Dropping to: " + targetDir);

		try {
			files = (List<File>) (tf.getTransferData(DataFlavor.javaFileListFlavor));
			if ((files == null) || (files.isEmpty()))
				return false;
			boolean copied=BrowserUtils.copyFiles((JComponent)support.getComponent(), files, targetDir);
			this.dlfsPanel.setSelectedPath(this.dlfsPanel.getSelectedPath());
			return copied;
		} catch (UnsupportedFlavorException e) {
			return false;
		} catch (Exception e) {
			e.printStackTrace();
			return false;
		}
	}

	protected Path getTargetDirectory(TransferSupport support) {
		Path targetDir = getTargetPath();
		DropLocation dropLocation = support.getDropLocation();
		if (dropLocation instanceof JTree.DropLocation) {
			JTree.DropLocation dl = (JTree.DropLocation) dropLocation;
			TreePath treePath = dl.getPath();
			if (treePath != null) {
				DirectoryTree.Node parent = (DirectoryTree.Node) treePath.getLastPathComponent();
				System.out.println("Parent: " + parent);
				targetDir = parent.getFilePath();
			}
		}
		return targetDir;
	}

	protected abstract Path getTargetPath();
}