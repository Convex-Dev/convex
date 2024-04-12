package convex.gui.dlfs;

import convex.gui.components.AbstractGUI;
import convex.gui.utils.Toolkit;
import net.miginfocom.swing.MigLayout;

@SuppressWarnings("serial")
public class DLFSGUI extends AbstractGUI {
	
	public DLFSGUI() {
		setLayout(new MigLayout());
		add(new DLFSPanel(),"dock center");
	}
	
	/**
	 * Launch the application.
	 * @param args Command line args
	 */
	public static void main(String[] args) {
		// call to set up Look and Feel
		Toolkit.init();
		DLFSGUI gui=new DLFSGUI();
		gui.run();
	}
	
	@Override public String getTitle() {
		return "Data Lattice";
	}

}
