package convex.gui.dlfs;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import javax.swing.JButton;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;

import convex.core.data.ACell;
import convex.core.data.AVector;
import convex.core.data.Hash;
import convex.core.util.ThreadUtils;
import convex.gui.state.StateExplorer;
import convex.gui.utils.SymbolIcon;
import convex.gui.utils.Toolkit;
import convex.lattice.fs.DLFileSystem;
import convex.lattice.fs.DLPath;
import net.miginfocom.swing.MigLayout;

/**
 * Manages the content for the DLFS file browser columns: directory tree,
 * file list, and preview panel.
 *
 * <p>Does not own layout — the caller (DLFSBrowser) places the scroll panes
 * and button panels into a column structure. This class handles content
 * creation, drive switching, and file operations.
 */
public class DLFSPanel {

	protected DLFileSystem fileSystem;

	DirectoryTree directoryTree;
	FileList fileList;
	PreviewPanel previewPanel;

	private DLPath selectedPath;
	private String driveName;

	// Scroll panes owned here, placed into columns by the caller
	final JScrollPane treeScrollPane;
	final JScrollPane filesScrollPane;
	final JScrollPane viewerScrollPane;

	// Button panels for each column
	final JPanel treeButtons;
	final JPanel filesButtons;
	final JPanel viewerButtons;

	public DLFSPanel(DLFileSystem dlfs) {
		this(dlfs, null);
	}

	public DLFSPanel(DLFileSystem dlfs, String driveName) {
		this.fileSystem = dlfs;
		this.driveName = driveName;
		this.selectedPath = dlfs.getRoot();

		previewPanel = new PreviewPanel();

		treeScrollPane = new JScrollPane();
		filesScrollPane = new JScrollPane();
		viewerScrollPane = new JScrollPane(previewPanel);

		// Build tree and file list content
		initTreeAndList(dlfs);
		treeScrollPane.setViewportView(directoryTree);
		filesScrollPane.setViewportView(fileList);

		// Button panels
		treeButtons = new JPanel(new MigLayout("insets 2", "[][]"));
		JButton newFolderBtn = new JButton(SymbolIcon.get(0xe2c7, Toolkit.SMALL_ICON_SIZE));
		newFolderBtn.setToolTipText("New Folder");
		newFolderBtn.addActionListener(e -> promptNewFolder());
		treeButtons.add(newFolderBtn);
		JButton refreshBtn = new JButton(SymbolIcon.get(0xe5d5, Toolkit.SMALL_ICON_SIZE));
		refreshBtn.setToolTipText("Refresh");
		refreshBtn.addActionListener(e -> refreshView());
		treeButtons.add(refreshBtn);

		filesButtons = new JPanel(new MigLayout("insets 2", "[]"));
		JButton deleteBtn = new JButton(SymbolIcon.get(0xe872, Toolkit.SMALL_ICON_SIZE));
		deleteBtn.setToolTipText("Delete");
		deleteBtn.addActionListener(e -> promptDeleteFile());
		filesButtons.add(deleteBtn);

		viewerButtons = new JPanel(new MigLayout("insets 2", "[]"));
		JButton infoBtn = new JButton(SymbolIcon.get(0xe88e, Toolkit.SMALL_ICON_SIZE));
		infoBtn.setToolTipText("Explore Node");
		infoBtn.addActionListener(e -> exploreSelectedNode());
		viewerButtons.add(infoBtn);

		directoryTree.setSelectionPath(directoryTree.getPathForRow(0));
		startBackgroundThread(dlfs);
	}

	/** Creates (or recreates) the directory tree and file list for a drive */
	private void initTreeAndList(DLFileSystem dlfs) {
		directoryTree = new DirectoryTree(dlfs, driveName);
		directoryTree.setTransferHandler(new DnDTransferHandler(this) {
			@Override
			protected Path getTargetPath() {
				return getSelectedPath();
			}
		});

		directoryTree.addTreeSelectionListener(e -> {
			DirectoryTree.Node node = (DirectoryTree.Node) directoryTree.getLastSelectedPathComponent();
			if (node == null) {
				setSelectedPath(null);
			} else {
				setSelectedPath(node.getFilePath());
			}
		});

		fileList = new FileList(selectedPath, p -> setSelectedPath(p));
		fileList.setTransferHandler(new DnDTransferHandler(this) {
			@Override
			protected Path getTargetPath() {
				return getSelectedPath();
			}
		});

		fileList.addListSelectionListener(e -> {
			Path p = fileList.getSelectedPath();
			previewPanel.setPath(p);
		});
	}

	/**
	 * Switches to display a different drive.
	 */
	public void setDrive(DLFileSystem newDrive) {
		setDrive(newDrive, null);
	}

	/**
	 * Switches to display a different drive with a given name.
	 * Swaps content within existing scroll panes — no layout rebuild.
	 */
	public void setDrive(DLFileSystem newDrive, String driveName) {
		if (newDrive == null) return;
		this.fileSystem = newDrive;
		this.driveName = driveName;
		this.selectedPath = newDrive.getRoot();

		initTreeAndList(newDrive);
		treeScrollPane.setViewportView(directoryTree);
		filesScrollPane.setViewportView(fileList);
		previewPanel.setPath(null);

		directoryTree.setSelectionPath(directoryTree.getPathForRow(0));

		startBackgroundThread(newDrive);
	}

	/** Last known root hash — used to detect external changes */
	private volatile Hash lastRootHash;

	private void startBackgroundThread(DLFileSystem dlfs) {
		lastRootHash = dlfs.getRootHash();
		ThreadUtils.runVirtual(() -> {
			try {
				while (dlfs.isOpen() && dlfs == fileSystem) {
					dlfs.updateTimestamp();

					// Check if lattice state changed (e.g. via WebDAV)
					Hash currentHash = dlfs.getRootHash();
					if (currentHash != null && !currentHash.equals(lastRootHash)) {
						lastRootHash = currentHash;
						SwingUtilities.invokeLater(this::refreshView);
					}

					Thread.sleep(200);
				}
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		});
	}

	public DLFileSystem getFileSystem() {
		return fileSystem;
	}

	void setSelectedPath(Path newPath) {
		if (!(newPath instanceof DLPath)) {
			selectedPath = null;
			return;
		}
		selectedPath = (DLPath) newPath;
		fileList.setDirectory(newPath);
		previewPanel.setPath(newPath);
	}

	// ========== UI Actions ==========

	void promptNewFolder() {
		Path selected = getSelectedPath();
		if (selected == null) return;
		if (!Files.isDirectory(selected)) selected = selected.getParent();
		if (selected == null) return;

		String name = JOptionPane.showInputDialog(null, "Folder name:", "New Folder", JOptionPane.PLAIN_MESSAGE);
		if (name == null || name.isBlank()) return;
		try {
			Files.createDirectory(selected.resolve(name.trim()));
			refreshView();
		} catch (IOException ex) {
			JOptionPane.showMessageDialog(null, "Error: " + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
		}
	}

	void exploreSelectedNode() {
		DLPath p = getSelectedPath();
		if (p == null) return;
		AVector<ACell> node = fileSystem.getNode(p);
		if (node != null) {
			StateExplorer.explore(node);
		} else {
			AVector<ACell> root = fileSystem.getNode(fileSystem.getRoot());
			if (root != null) StateExplorer.explore(root);
		}
	}

	void promptDeleteFile() {
		Path selected = fileList.getSelectedPath();
		if (selected == null) return;
		int confirm = JOptionPane.showConfirmDialog(null,
			"Delete '" + selected.getFileName() + "'?", "Confirm Delete",
			JOptionPane.YES_NO_OPTION);
		if (confirm != JOptionPane.YES_OPTION) return;
		try {
			Files.deleteIfExists(selected);
			refreshView();
		} catch (IOException ex) {
			JOptionPane.showMessageDialog(null, "Error: " + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
		}
	}

	/**
	 * Creates a standalone JPanel view with the 3 browser columns in split panes.
	 * For use in tabs or other containers that need a single Swing component.
	 */
	public JPanel createView() {
		JPanel treeCol = DLFSBrowser.titledColumn("Directory Tree", treeScrollPane, treeButtons);
		JPanel filesCol = DLFSBrowser.titledColumn("Files", filesScrollPane, filesButtons);
		JPanel viewerCol = DLFSBrowser.titledColumn("Viewer", viewerScrollPane, viewerButtons);

		javax.swing.JSplitPane innerSplit = new javax.swing.JSplitPane(
			javax.swing.JSplitPane.HORIZONTAL_SPLIT, filesCol, viewerCol);
		innerSplit.setResizeWeight(0.4);
		javax.swing.JSplitPane outerSplit = new javax.swing.JSplitPane(
			javax.swing.JSplitPane.HORIZONTAL_SPLIT, treeCol, innerSplit);
		outerSplit.setResizeWeight(0.35);

		JPanel view = new JPanel(new MigLayout("fill, insets 0"));
		view.add(outerSplit, "grow");
		return view;
	}

	public DLPath getSelectedPath() {
		return selectedPath;
	}

	public void refreshView() {
		directoryTree.refreshSelectedNode();
		fileList.setDirectory(selectedPath);
		previewPanel.setPath(selectedPath);
		// Update hash so the background thread doesn't re-trigger
		lastRootHash = fileSystem.getRootHash();
	}
}
