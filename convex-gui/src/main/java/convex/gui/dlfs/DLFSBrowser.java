package convex.gui.dlfs;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;

import javax.swing.JFrame;
import javax.swing.JMenu;
import javax.swing.JMenuBar;

import convex.core.data.ACell;
import convex.core.data.AVector;
import convex.dlfs.DLFS;
import convex.dlfs.DLFileSystem;
import convex.dlfs.DLPath;
import convex.gui.components.AbstractGUI;
import convex.gui.state.StateExplorer;
import convex.gui.utils.Toolkit;
import net.miginfocom.swing.MigLayout;

@SuppressWarnings("serial")
public class DLFSBrowser extends AbstractGUI {
	
	protected DLFileSystem drive;
	
	public static ArrayList<DLFileSystem> allDrives=new ArrayList<>(); 

	public JMenuBar menuBar=new JMenuBar();
	public JMenu fileMenu=new JMenu("File");
	public JMenu driveMenu=new JMenu("Drive");
	public JMenu helpMenu=new JMenu("Help");
	protected DLFSPanel panel;
	
	public DLFSBrowser(DLFileSystem drive) {
		super (	"Data Lattice");

		DLFS.provider().fileSystems.put("drive",drive);
		
		allDrives.add(drive);
		setLayout(new MigLayout());
		this.drive=drive;
		panel=new DLFSPanel(drive);
		add(panel,"dock center");
		
		fileMenu.add(Toolkit.makeMenu("Explore Node...",()->{
			Path p=panel.getSelectedPath();
			if (p instanceof DLPath) {
				AVector<ACell> node=drive.getNode((DLPath) p);
				if (node!=null) {
					StateExplorer.explore(node);
				} else {
					StateExplorer.explore(drive.getNode(drive.getRoot()));
				}
			}
			panel.refreshView();
		}));	
		// fileMenu.addSeparator();
		fileMenu.add(Toolkit.makeMenu("Delete",()->{
			Path p=panel.getSelectedPath();
			try {
				Files.deleteIfExists(p);
			} catch (IOException e) {
				System.out.println("Can't delete "+p+ " : "+e.getMessage());
			}
			panel.refreshView();
		}));		
		menuBar.add(fileMenu);
		
		driveMenu.add(Toolkit.makeMenu("Clone",()->new DLFSBrowser(drive.clone()).run()));
		driveMenu.add(Toolkit.makeMenu("Sync",()->{
			for (DLFileSystem other: allDrives) {
				if (other!=drive) {
					System.out.println("Replicating!!");
					drive.replicate(other);
				}
			}
			panel.refreshView();
		}));
		menuBar.add(driveMenu);
		
		menuBar.add(Toolkit.makeMenu("Sync!",()->{
			for (DLFileSystem other: allDrives) {
				if (other!=drive) {
					System.out.println("Replicating!!");
					drive.replicate(other);
				}
			}
			panel.refreshView();
		}));
	}
	
	@Override
	public void afterRun() {
		getFrame().setJMenuBar(menuBar);
	}
	
	public DLFileSystem getDrive() {
		return drive;
	}

	public DLFSBrowser() {
		this(createDemoDrive());
	}


	public static DLFileSystem createDemoDrive() {
		DLFileSystem drive=DLFS.createLocal();
		drive.updateTimestamp();
		
		DLPath p=drive.getRoot();
		// Path p=new File(".").toPath();
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
	
	/**
	 * Launch the application.
	 * @param args Command line args
	 */
	public static void main(String[] args) {
		// call to set up Look and Feel
		Toolkit.init();
		
		DLFSBrowser gui=new DLFSBrowser();
		gui.run();
		gui.waitForClose();
		System.exit(0);
	}

	@Override
	public void setupFrame(JFrame frame) {
		frame.getContentPane().setLayout(new MigLayout());
		frame.getContentPane().add(this,"dock center");
	}
	

}
