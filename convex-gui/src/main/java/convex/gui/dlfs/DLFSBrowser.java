package convex.gui.dlfs;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.ListSelectionModel;
import javax.swing.filechooser.FileNameExtensionFilter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.core.crypto.AKeyPair;
import convex.core.data.ACell;
import convex.core.data.AHashMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.AccountKey;
import convex.core.data.Maps;
import convex.core.data.SignedData;
import convex.core.data.Strings;
import convex.core.data.prim.CVMLong;
import convex.core.util.FileUtils;
import convex.dlfs.DLFSServer;
import convex.etch.EtchStore;
import convex.gui.components.AbstractGUI;
import convex.gui.state.StateExplorer;
import convex.gui.utils.SymbolIcon;
import convex.gui.utils.Toolkit;
import convex.lattice.LatticeContext;
import convex.lattice.cursor.ALatticeCursor;
import convex.lattice.cursor.SignedCursor;
import convex.lattice.fs.DLFS;
import convex.lattice.fs.DLFSLattice;
import convex.lattice.fs.DLFSNode;
import convex.lattice.fs.DLFileSystem;
import convex.lattice.fs.DLPath;
import convex.lattice.fs.impl.DLFSLocal;
import convex.lattice.generic.MapLattice;
import convex.lattice.generic.OwnerLattice;
import convex.node.NodeConfig;
import convex.node.NodeServer;
import net.miginfocom.swing.MigLayout;

/**
 * Data Lattice File System browser showcasing a {@link NodeServer} hosting
 * DLFS as a persistent lattice node with a WebDAV interface on top.
 *
 * <p>The browser runs a NodeServer backed by an Etch store (default
 * {@code ~/.convex/dlfs/dlfs.db}). The lattice uses an
 * {@link OwnerLattice} wrapping a {@link MapLattice} of
 * {@link DLFSLattice} — matching the {@code :fs} branch of
 * {@code Lattice.ROOT}. Drive updates are signed with a local key pair.
 *
 * <p>A {@link SignedCursor} transparently handles signing when writes
 * propagate up through the OwnerLattice. Each drive is a
 * {@link DLFSLocal} backed by a {@link convex.lattice.cursor.PathCursor}
 * into the signed drives map.
 *
 * <p>A WebDAV server starts alongside the viewer, exposing drives over HTTP.
 * Since both GUI and WebDAV share the same DLFSLocal instances, operations
 * through either path are automatically consistent.
 *
 * <p>The NodeServer handles persistence (via {@code LatticePropagator}) and
 * can sync with remote lattice peers over the binary protocol.
 */
@SuppressWarnings("serial")
public class DLFSBrowser extends AbstractGUI {

	private static final Logger log = LoggerFactory.getLogger(DLFSBrowser.class);

	/** Default store path */
	static final String DEFAULT_STORE_PATH = "~/.convex/dlfs/dlfs.db";

	/** Key file extension (placed alongside the store file) */
	static final String KEY_FILE_EXTENSION = ".key";

	/** Inner lattice: map of drive names to DLFS trees */
	static final MapLattice<AString, AVector<ACell>> DRIVES_MAP_LATTICE =
		MapLattice.create(DLFSLattice.INSTANCE);

	/** Outer lattice: owner-signed map of drives (matches Lattice.ROOT :fs) */
	static final OwnerLattice<AHashMap<AString, AVector<ACell>>> OWNER_LATTICE =
		OwnerLattice.create(DRIVES_MAP_LATTICE);

	/** NodeServer hosting the DLFS lattice with persistence and networking */
	private NodeServer<AHashMap<ACell, SignedData<AHashMap<AString, AVector<ACell>>>>> nodeServer;

	/** Local key pair for signing drive updates */
	private AKeyPair keyPair;

	/** Owner key (derived from keyPair) */
	private AccountKey ownerKey;

	/** Cursor into the owner's unsigned drives map (writes re-sign via SignedCursor) */
	private ALatticeCursor<AHashMap<AString, AVector<ACell>>> drivesCursor;

	/** Active drives keyed by name (insertion ordered) */
	private final Map<String, DLFSLocal> drives = new LinkedHashMap<>();

	/** WebDAV server sharing our drives */
	private DLFSServer webdavServer;

	/** Currently selected drive name */
	private String currentDriveName;

	/** Path to current store file */
	private File storeFile;

	/** Current Etch store (owned by this browser — must be closed on shutdown) */
	private EtchStore etchStore;

	// UI
	private final JMenuBar menuBar = new JMenuBar();
	private final JList<String> driveList;
	private final DefaultListModel<String> driveListModel;
	private DLFSPanel panel;
	private javax.swing.JTextField webdavField;
	private javax.swing.JTextField uncField;
	private javax.swing.JTextField storeField;

	public DLFSBrowser() {
		this(FileUtils.getFile(DEFAULT_STORE_PATH));
	}

	public DLFSBrowser(File storeFile) {
		super("DLFS Browser");

		// Launch NodeServer with Etch store
		openStore(storeFile);

		// Start WebDAV, seeding with existing drives
		startWebDAV();

		// Layout
		setLayout(new MigLayout("fill, insets 0", "[grow]", "[grow][]"));

		// -- Create file browser content (manages tree, file list, preview) --
		List<String> driveNames = listDrives();
		currentDriveName = driveNames.isEmpty() ? null : driveNames.get(0);
		panel = new DLFSPanel(
			currentDriveName != null ? getDrive(currentDriveName) : DLFS.createLocal(),
			currentDriveName);

		// -- Drives column --
		driveListModel = new DefaultListModel<>();
		driveList = new JList<>(driveListModel);
		driveList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
		driveList.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
		driveList.addListSelectionListener(e -> {
			if (e.getValueIsAdjusting()) return;
			String sel = driveList.getSelectedValue();
			if (sel != null && !sel.equals(currentDriveName)) {
				switchDrive(sel);
			}
		});
		refreshDriveList();

		JPanel driveButtons = new JPanel(new MigLayout("insets 2", "[][]"));
		JButton newDriveBtn = new JButton(SymbolIcon.get(0xe2cc, Toolkit.SMALL_ICON_SIZE));
		newDriveBtn.setToolTipText("New Drive");
		newDriveBtn.addActionListener(e -> promptNewDrive());
		driveButtons.add(newDriveBtn);
		JButton deleteDriveBtn = new JButton(SymbolIcon.get(0xe872, Toolkit.SMALL_ICON_SIZE));
		deleteDriveBtn.setToolTipText("Delete Drive");
		deleteDriveBtn.addActionListener(e -> promptDeleteDrive());
		driveButtons.add(deleteDriveBtn);

		// -- All 4 columns as titled panels --
		JPanel drivesCol = titledColumn("Drives", new JScrollPane(driveList), driveButtons);
		JPanel treeCol = titledColumn("Directory Tree", panel.treeScrollPane, panel.treeButtons);
		JPanel filesCol = titledColumn("Files", panel.filesScrollPane, panel.filesButtons);
		JPanel viewerCol = titledColumn("Viewer", panel.viewerScrollPane, panel.viewerButtons);

		// -- Chain of JSplitPanes: drives | tree | files | viewer --
		JSplitPane innerSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, filesCol, viewerCol);
		innerSplit.setResizeWeight(0.4);
		JSplitPane midSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, treeCol, innerSplit);
		midSplit.setResizeWeight(0.35);
		JSplitPane outerSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, drivesCol, midSplit);
		outerSplit.setDividerLocation(120);
		outerSplit.setResizeWeight(0.0);
		add(outerSplit, "grow, wrap");

		// -- Status bar --
		JPanel statusBar = new JPanel(new MigLayout("insets 4", "[][grow][][grow][][grow][]"));
		statusBar.add(new JLabel("Store:"));
		storeField = new javax.swing.JTextField();
		storeField.setEditable(false);
		storeField.setFont(Toolkit.MONO_FONT);
		statusBar.add(storeField, "growx");
		statusBar.add(new JLabel("WebDAV:"));
		webdavField = new javax.swing.JTextField();
		webdavField.setEditable(false);
		webdavField.setFont(Toolkit.MONO_FONT);
		statusBar.add(webdavField, "growx");
		statusBar.add(new JLabel("UNC Path:"));
		uncField = new javax.swing.JTextField();
		uncField.setEditable(false);
		uncField.setFont(Toolkit.MONO_FONT);
		statusBar.add(uncField, "growx");
		JButton exploreBtn = new JButton(SymbolIcon.get(0xe89e, Toolkit.SMALL_ICON_SIZE));
		exploreBtn.setToolTipText("Open in file explorer");
		exploreBtn.addActionListener(e -> openInExplorer());
		statusBar.add(exploreBtn);
		updateStatusBar();
		add(statusBar, "growx");

		// -- Menus --
		JMenu fileMenu = new JMenu("File");
		fileMenu.add(Toolkit.makeMenu("Change Store...", this::promptChangeStore));
		fileMenu.addSeparator();
		fileMenu.add(Toolkit.makeMenu("Explore Node...", () -> {
			DLFileSystem drive = getCurrentDrive();
			if (drive == null) return;
			Path p = panel.getSelectedPath();
			if (p instanceof DLPath) {
				AVector<ACell> node = drive.getNode((DLPath) p);
				if (node != null) {
					StateExplorer.explore(node);
				} else {
					StateExplorer.explore(drive.getNode(drive.getRoot()));
				}
			}
			panel.refreshView();
		}));
		fileMenu.add(Toolkit.makeMenu("New Folder...", () -> panel.promptNewFolder()));
		fileMenu.add(Toolkit.makeMenu("Delete...", () -> panel.promptDeleteFile()));
		menuBar.add(fileMenu);

		JMenu driveMenu = new JMenu("Drive");
		driveMenu.add(Toolkit.makeMenu("New Drive...", this::promptNewDrive));
		driveMenu.add(Toolkit.makeMenu("Delete Drive", this::promptDeleteDrive));
		menuBar.add(driveMenu);
	}

	// ========== NodeServer / Store ==========

	/**
	 * Opens an Etch store and launches a NodeServer for the DLFS lattice.
	 * Restores drives from the store if data exists, otherwise creates a demo drive.
	 */
	private void openStore(File file) {
		this.storeFile = file;
		try {
			etchStore = EtchStore.create(FileUtils.ensureFilePath(file));
		} catch (java.nio.channels.OverlappingFileLockException e) {
			log.warn("Store is already locked: {}", file);
			int choice = JOptionPane.showOptionDialog(this,
				"Store file is already in use:\n" + file.getAbsolutePath()
					+ "\n\nAnother DLFS browser may be open. Use a temporary store instead?",
				"Store Locked",
				JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE,
				null, new String[]{"Use Temp Store", "Cancel"}, "Use Temp Store");
			if (choice == 0) {
				try {
					etchStore = EtchStore.createTemp("dlfs-temp");
					this.storeFile = new File(etchStore.getFileName());
					log.info("Using temporary store: {}", storeFile);
				} catch (IOException e2) {
					log.error("Failed to create temporary store", e2);
					throw new RuntimeException("Failed to create temporary DLFS store", e2);
				}
			} else {
				throw new RuntimeException("Store is locked: " + file);
			}
		} catch (Exception e) {
			log.error("Failed to open store: {}", file, e);
			throw new RuntimeException("Failed to open DLFS store: " + file, e);
		}

		try {
			// Generate a local key pair for signing drive updates
			keyPair = AKeyPair.generate();
			ownerKey = keyPair.getAccountKey();

			// Local-only mode (port -1): persistence + cursor, no Netty network server
			NodeConfig localConfig = NodeConfig.create(Maps.of(NodeConfig.PORT, CVMLong.create(-1)));
			nodeServer = new NodeServer<>(OWNER_LATTICE, etchStore, localConfig);

			// Set merge context with signing key for OwnerLattice verification
			LatticeContext ctx = LatticeContext.create(null, keyPair);
			nodeServer.setMergeContext(ctx);
			nodeServer.launch();

			// Navigate to owner's signed entry, wrap in SignedCursor
			ALatticeCursor<AHashMap<ACell, SignedData<AHashMap<AString, AVector<ACell>>>>> rootCursor =
				nodeServer.getCursor();
			@SuppressWarnings("unchecked")
			ALatticeCursor<SignedData<AHashMap<AString, AVector<ACell>>>> ownerEntry =
				(ALatticeCursor<SignedData<AHashMap<AString, AVector<ACell>>>>) (ALatticeCursor<?>) rootCursor.path(ownerKey);
			drivesCursor = SignedCursor.create(ownerEntry, DRIVES_MAP_LATTICE, ctx);

			// Rebuild drives from restored lattice state
			drives.clear();
			AHashMap<AString, AVector<ACell>> driveMap = drivesCursor.get();
			if (driveMap != null && !driveMap.isEmpty()) {
				for (Map.Entry<AString, AVector<ACell>> entry : driveMap.entrySet()) {
					String name = entry.getKey().toString();
					ALatticeCursor<AVector<ACell>> driveCursor = drivesCursor.path(entry.getKey());
					DLFSLocal driveFS = new DLFSLocal(DLFS.provider(), name, driveCursor);
					driveFS.updateTimestamp();
					drives.put(name, driveFS);
				}
				log.info("Restored {} drives from store: {}", drives.size(), file);
			} else {
				// Fresh store — create demo drive
				createDrive("demo");
				populateDemoDrive(getDrive("demo"));
				log.info("Created new DLFS store with demo drive: {}", file);
			}
		} catch (Exception e) {
			log.error("Failed to launch NodeServer: {}", file, e);
			throw new RuntimeException("Failed to open DLFS store: " + file, e);
		}
	}

	/**
	 * Closes the current NodeServer (persisting state) and opens a new store.
	 */
	private void switchStore(File newFile) {
		// Close current NodeServer — persists final state
		closeNodeServer();

		// Close WebDAV
		if (webdavServer != null) {
			webdavServer.close();
			webdavServer = null;
		}

		// Open new store
		openStore(newFile);

		// Restart WebDAV with new drives
		startWebDAV();

		// Refresh UI
		refreshDriveList();
		List<String> driveNames = listDrives();
		if (!driveNames.isEmpty()) {
			currentDriveName = driveNames.get(0);
			driveList.setSelectedValue(currentDriveName, true);
			panel.setDrive(getDrive(currentDriveName), currentDriveName);
		} else {
			currentDriveName = null;
		}
		updateStatusBar();
	}

	private void closeNodeServer() {
		if (nodeServer != null) {
			try {
				nodeServer.close();
			} catch (IOException e) {
				log.warn("Error closing NodeServer: {}", e.getMessage());
			}
			nodeServer = null;
		}
		if (etchStore != null) {
			etchStore.close();
			etchStore = null;
		}
	}

	// ========== Drive Management ==========

	/**
	 * Creates a new drive backed by a cursor descended from the signed drives map.
	 * @param name Drive name
	 * @return true if created, false if already exists
	 */
	public boolean createDrive(String name) {
		if (drives.containsKey(name)) return false;

		AString cvmName = Strings.create(name);
		ALatticeCursor<AVector<ACell>> driveCursor = drivesCursor.path(cvmName);
		driveCursor.set(DLFSNode.createDirectory(CVMLong.ZERO));

		DLFSLocal driveFS = new DLFSLocal(DLFS.provider(), name, driveCursor);
		driveFS.updateTimestamp();
		drives.put(name, driveFS);

		// Register in WebDAV if running
		if (webdavServer != null) {
			webdavServer.getDriveManager().seedDrive(null, name, driveFS);
		}
		return true;
	}

	/**
	 * Gets an existing drive by name.
	 */
	public DLFileSystem getDrive(String name) {
		return drives.get(name);
	}

	/**
	 * Gets the currently displayed drive.
	 */
	public DLFileSystem getCurrentDrive() {
		if (currentDriveName == null) return null;
		return drives.get(currentDriveName);
	}

	/**
	 * Lists all drive names.
	 */
	public List<String> listDrives() {
		return new ArrayList<>(drives.keySet());
	}

	/**
	 * Deletes a drive.
	 */
	public boolean deleteDrive(String name) {
		DLFSLocal removed = drives.remove(name);
		if (removed == null) return false;

		// Clear from lattice
		AString cvmName = Strings.create(name);
		ALatticeCursor<AVector<ACell>> cursor = drivesCursor.path(cvmName);
		cursor.set(null);

		// Remove from WebDAV
		if (webdavServer != null) {
			webdavServer.getDriveManager().deleteDrive(null, name);
		}
		return true;
	}

	// ========== UI Actions ==========

	private void switchDrive(String name) {
		DLFileSystem drive = getDrive(name);
		if (drive == null) return;
		currentDriveName = name;
		panel.setDrive(drive, name);
	}

	private void promptNewDrive() {
		String name = JOptionPane.showInputDialog(this, "Drive name:", "New Drive", JOptionPane.PLAIN_MESSAGE);
		if (name == null || name.isBlank()) return;
		name = name.trim();
		if (!createDrive(name)) {
			JOptionPane.showMessageDialog(this, "Drive '" + name + "' already exists.", "Error", JOptionPane.ERROR_MESSAGE);
			return;
		}
		refreshDriveList();
		driveList.setSelectedValue(name, true);
		switchDrive(name);
	}

	private void promptDeleteDrive() {
		if (currentDriveName == null) return;
		int confirm = JOptionPane.showConfirmDialog(this,
			"Delete drive '" + currentDriveName + "'?", "Confirm Delete",
			JOptionPane.YES_NO_OPTION);
		if (confirm != JOptionPane.YES_OPTION) return;

		deleteDrive(currentDriveName);
		refreshDriveList();

		List<String> remaining = listDrives();
		if (!remaining.isEmpty()) {
			driveList.setSelectedValue(remaining.get(0), true);
			switchDrive(remaining.get(0));
		} else {
			currentDriveName = null;
		}
	}

	private void promptChangeStore() {
		JFileChooser chooser = new JFileChooser();
		chooser.setDialogTitle("Select DLFS Store");
		chooser.setFileFilter(new FileNameExtensionFilter("Etch database (*.db)", "db"));
		if (storeFile != null) {
			chooser.setCurrentDirectory(storeFile.getParentFile());
			chooser.setSelectedFile(storeFile);
		}

		JCheckBox createNew = new JCheckBox("Create new store if file doesn't exist", true);
		chooser.setAccessory(createNew);

		int result = chooser.showOpenDialog(this);
		if (result != JFileChooser.APPROVE_OPTION) return;

		File selected = chooser.getSelectedFile();
		if (!selected.exists() && !createNew.isSelected()) {
			JOptionPane.showMessageDialog(this,
				"File does not exist and 'Create new' is unchecked.",
				"Error", JOptionPane.ERROR_MESSAGE);
			return;
		}

		switchStore(selected);
	}

	private void refreshDriveList() {
		List<String> driveNames = listDrives();
		String selected = currentDriveName;
		driveListModel.clear();
		for (String d : driveNames) driveListModel.addElement(d);
		if (selected != null && driveNames.contains(selected)) {
			driveList.setSelectedValue(selected, true);
		}
	}

	// ========== WebDAV ==========

	private void startWebDAV() {
		try {
			webdavServer = DLFSServer.create(null);
			// Seed all existing lattice-backed drives
			for (Map.Entry<String, DLFSLocal> entry : drives.entrySet()) {
				webdavServer.getDriveManager().seedDrive(null, entry.getKey(), entry.getValue());
			}
			webdavServer.start(0);
			log.info("WebDAV server started on port {}", webdavServer.getPort());
		} catch (Exception e) {
			log.warn("Failed to start WebDAV server: {}", e.getMessage());
			webdavServer = null;
		}
	}

	String getWebDAVUrl() {
		if (webdavServer == null) return null;
		return "http://localhost:" + webdavServer.getPort() + "/dlfs/";
	}

	private void updateStatusBar() {
		storeField.setText(storeFile != null ? storeFile.getAbsolutePath() : "None");
		String url = getWebDAVUrl();
		webdavField.setText(url != null ? url : "Not available");
		String unc = getUNCPath();
		uncField.setText(unc != null ? unc : "Not available");
	}

	String getUNCPath() {
		if (webdavServer == null) return null;
		int port = webdavServer.getPort();
		if (port == 80) {
			return "\\\\localhost\\dlfs\\";
		}
		return "\\\\localhost@" + port + "\\dlfs\\";
	}

	private void openInExplorer() {
		String url = getWebDAVUrl();
		if (url == null) return;
		try {
			String os = System.getProperty("os.name", "").toLowerCase();
			if (os.contains("win")) {
				Runtime.getRuntime().exec(new String[]{"explorer.exe", getUNCPath()});
			} else if (os.contains("mac")) {
				Runtime.getRuntime().exec(new String[]{"open", url});
			} else {
				Runtime.getRuntime().exec(new String[]{"xdg-open", url});
			}
		} catch (IOException ex) {
			log.warn("Failed to open file explorer: {}", ex.getMessage());
		}
	}

	// ========== Layout helpers ==========

	/**
	 * Creates a column panel with consistent layout: title, content, optional buttons.
	 * Uses MigLayout with min width 0 so split pane dividers move freely.
	 */
	static JPanel titledColumn(String title, javax.swing.JComponent content, JPanel buttons) {
		String rows = (buttons != null) ? "[][grow][]" : "[][grow]";
		JPanel p = new JPanel(new MigLayout("fill, insets 0", "[grow,fill,0:0:]", rows));
		JLabel header = new JLabel(title);
		header.setBorder(BorderFactory.createEmptyBorder(2, 4, 2, 4));
		p.add(header, "growx, wrap");
		p.add(content, "grow, wmin 0, wrap");
		if (buttons != null) p.add(buttons, "growx");
		return p;
	}

	// ========== Demo Data ==========

	static void populateDemoDrive(DLFileSystem drive) {
		if (drive == null) return;
		drive.updateTimestamp();
		DLPath p = drive.getRoot();
		try {
			Files.createDirectory(p.resolve("training"));
			Files.createDirectory(p.resolve("models"));
			Files.createDirectory(p.resolve("input"));
			Files.createDirectory(p.resolve("provenance"));
			Files.createDirectory(p.resolve("pytools"));
			Files.createDirectory(p.resolve("cuda"));
			Files.createFile(p.resolve("models/ace1.tensor"));
			Files.createFile(p.resolve("models/ace2.tensor"));
			Files.createDirectories(p.resolve("models/old"));
			Files.createDirectories(p.resolve("models/experimental"));
			Files.createFile(p.resolve("tombstone"));
			Files.delete(p.resolve("tombstone"));
		} catch (Exception e) {
			log.warn("Error populating demo drive", e);
		}
	}

	/**
	 * Static factory for a standalone demo drive (backwards compatibility).
	 */
	public static DLFileSystem createDemoDrive() {
		DLFileSystem drive = DLFS.createLocal();
		drive.updateTimestamp();
		DLPath p = drive.getRoot();
		try {
			Files.createDirectory(p.resolve("training"));
			Files.createDirectory(p.resolve("models"));
			Files.createDirectory(p.resolve("input"));
			Files.createDirectory(p.resolve("provenance"));
			Files.createDirectory(p.resolve("pytools"));
			Files.createDirectory(p.resolve("cuda"));
			Files.createFile(p.resolve("models/ace1.tensor"));
			Files.createFile(p.resolve("models/ace2.tensor"));
			Files.createDirectories(p.resolve("models/old"));
			Files.createDirectories(p.resolve("models/experimental"));
			Files.createFile(p.resolve("tombstone"));
			Files.delete(p.resolve("tombstone"));
		} catch (Exception e) {
			e.printStackTrace();
		}
		return drive;
	}

	// ========== Lifecycle ==========

	@Override
	public void afterRun() {
		getFrame().setJMenuBar(menuBar);
	}

	@Override
	public void setupFrame(JFrame frame) {
		frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
		frame.getContentPane().setLayout(new MigLayout());
		frame.getContentPane().add(this, "dock center");
		frame.addWindowListener(new java.awt.event.WindowAdapter() {
			@Override
			public void windowClosing(java.awt.event.WindowEvent e) {
				shutdown();
			}
		});
	}

	/** Idempotent shutdown — safe to call from windowClosing, close(), etc. */
	private void shutdown() {
		if (panel != null) {
			panel.close();
		}
		if (webdavServer != null) {
			webdavServer.close();
			webdavServer = null;
		}
		closeNodeServer();
	}

	@Override
	public void close() {
		shutdown();
	}

	public static void main(String[] args) {
		Toolkit.init();
		DLFSBrowser gui = new DLFSBrowser();
		gui.run();
		gui.waitForClose();
		System.exit(0);
	}
}
