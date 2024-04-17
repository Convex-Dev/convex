package convex.gui.dlfs;

import java.nio.file.Files;

import convex.dlfs.DLFS;
import convex.dlfs.DLPath;
import convex.dlfs.impl.DLFSLocal;
import convex.gui.components.AbstractGUI;
import convex.gui.utils.Toolkit;
import net.miginfocom.swing.MigLayout;

@SuppressWarnings("serial")
public class DLFSBrowser extends AbstractGUI {
	
	protected DLFSLocal drive;

	public DLFSBrowser(DLFSLocal drive) {
		setLayout(new MigLayout());
		this.drive=drive;
		add(new DLFSPanel(drive),"dock center");
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
		
		DLFSLocal demoDrive=createDemoDrive();
		DLFSBrowser gui=new DLFSBrowser(demoDrive);
		gui.run();
	}
	
	@Override public String getTitle() {
		return "Data Lattice";
	}

}
