package convex.gui.dlfs;

import java.awt.event.ActionEvent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;

import javax.swing.AbstractAction;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;

import convex.core.data.ACell;
import convex.core.data.AVector;
import convex.dlfs.DLFS;
import convex.dlfs.DLPath;
import convex.dlfs.impl.DLFSLocal;
import convex.gui.components.AbstractGUI;
import convex.gui.peer.windows.state.StateExplorer;
import convex.gui.utils.Toolkit;
import net.miginfocom.swing.MigLayout;

@SuppressWarnings("serial")
public class DLFSBrowser extends AbstractGUI {
	
	protected DLFSLocal drive;
	
	protected static ArrayList<DLFSLocal> allDrives=new ArrayList<>(); 

	public JMenuBar menuBar=new JMenuBar();
	public JMenu fileMenu=new JMenu("File");
	public JMenu driveMenu=new JMenu("Drive");
	public JMenu helpMenu=new JMenu("Help");
	protected DLFSPanel panel;
	
	public DLFSBrowser(DLFSLocal drive) {
		allDrives.add(drive);
		setLayout(new MigLayout());
		this.drive=drive;
		panel=new DLFSPanel(drive);
		add(panel,"dock center");
		
		fileMenu.add(makeMenu("Explore Node...",()->{
			Path p=panel.fileList.getSelectedPath();
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
		fileMenu.add(makeMenu("Delete",()->{
			Path p=panel.fileList.getSelectedPath();
			try {
				Files.deleteIfExists(p);
			} catch (IOException e) {
				System.out.println("Can't delete "+p+ " : "+e.getMessage());
			}
			panel.refreshView();
		}));		
		menuBar.add(fileMenu);
		
		driveMenu.add(makeMenu("Clone",()->new DLFSBrowser(drive.clone()).run()));
		driveMenu.add(makeMenu("Sync",()->{
			for (DLFSLocal other: allDrives) {
				if (other!=drive) {
					System.out.println("Replicating!!");
					drive.replicate(other);
				}
			}
			panel.refreshView();
		}));
		menuBar.add(driveMenu);
		
		menuBar.add(makeMenu("Sync!",()->{
			for (DLFSLocal other: allDrives) {
				if (other!=drive) {
					System.out.println("Replicating!!");
					drive.replicate(other);
				}
			}
			panel.refreshView();
		}));
		
		getFrame().setJMenuBar(menuBar);
		
	}
	

	public DLFSBrowser() {
		this(createDemoDrive());
	}


	protected JMenuItem makeMenu(String name,Runnable op) {
		JMenuItem mi= new JMenuItem(name);
		mi.setAction(new AbstractAction(name) {

			@Override
			public void actionPerformed(ActionEvent e) {
				op.run();
			}
			
		} );
		return mi;
	}
	
	
	
	
	public static DLFSLocal createDemoDrive() {
		DLFSLocal drive=DLFS.createLocal();
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
	}
	
	@Override public String getTitle() {
		return "Data Lattice";
	}

}
