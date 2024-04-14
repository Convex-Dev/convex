package convex.gui.dlfs;

import java.nio.file.Files;
import java.nio.file.Path;

import convex.dlfs.DLFS;
import convex.gui.components.AbstractGUI;
import convex.gui.utils.Toolkit;
import net.miginfocom.swing.MigLayout;

@SuppressWarnings("serial")
public class DLFSBrowser extends AbstractGUI {
	
	public DLFSBrowser() {
		setLayout(new MigLayout());
		Path p=DLFS.createLocal().getRoot();
		// Path p=new File(".").toPath();
		try {
			Files.createFile(p.resolve("bob"));
			
		} catch (Exception e) {
			e.printStackTrace();
		}
		add(new DLFSPanel(p),"dock center");
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
