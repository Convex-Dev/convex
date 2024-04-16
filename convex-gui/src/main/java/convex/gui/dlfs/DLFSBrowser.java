package convex.gui.dlfs;

import java.nio.file.Files;

import convex.dlfs.DLFS;
import convex.dlfs.DLPath;
import convex.gui.components.AbstractGUI;
import convex.gui.utils.Toolkit;
import net.miginfocom.swing.MigLayout;

@SuppressWarnings("serial")
public class DLFSBrowser extends AbstractGUI {
	
	public DLFSBrowser() {
		setLayout(new MigLayout());
		DLPath p=DLFS.createLocal().getRoot();
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
			
		} catch (Exception e) {
			e.printStackTrace();
		}
		add(new DLFSPanel(p.getFileSystem()),"dock center");
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
