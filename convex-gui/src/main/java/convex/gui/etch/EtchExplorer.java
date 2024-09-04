package convex.gui.etch;

import java.awt.BorderLayout;

import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.JTabbedPane;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.core.store.Stores;
import convex.etch.EtchStore;
import convex.gui.components.AbstractGUI;
import convex.gui.models.StateModel;
import net.miginfocom.swing.MigLayout;

/**
 * A Client application for exploring an Etch store
 */
@SuppressWarnings("serial")
public class EtchExplorer extends AbstractGUI {

	public static final Logger log = LoggerFactory.getLogger(EtchExplorer.class.getName());

	public static long maxBlock = 0;

	/**
	 * Launch the application.
	 * @param args Command line args
	 */
	public static void main(String[] args) {
		// call to set up Look and Feel
		convex.gui.utils.Toolkit.init();

		EtchExplorer gui=new EtchExplorer();
		gui.run();
		gui.waitForClose();
		System.exit(0);
	}

	/*
	 * Main component panel
	 */
	JPanel panel = new JPanel();
 
	private static StateModel<EtchStore> etchState = StateModel.create((EtchStore)Stores.getGlobalStore());
	
	DatabasePanel homePanel = new DatabasePanel(this);
	JTabbedPane tabs = new JTabbedPane();
	JPanel mainPanel = new JPanel();

	
	/**
	 * Create the application.
	 */
	public EtchExplorer() {
		super ("Etch Explorer");
		
		setLayout(new BorderLayout());
		this.add(tabs, BorderLayout.CENTER);

		tabs.add("Database", homePanel);
	}

	public void switchPanel(String title) {
		int n = tabs.getTabCount();
		for (int i = 0; i < n; i++) {
			if (tabs.getTitleAt(i).contentEquals(title)) {
				tabs.setSelectedIndex(i);
				return;
			}
		}
		System.err.println("Missing tab: " + title);
	}

	public StateModel<EtchStore> getEtchState() {
		return etchState;
	}

	public EtchStore getStore() {
		return etchState.getValue();
	}
	
	public void setStore(EtchStore newEtch) {
		EtchStore e=etchState.getValue();
		e.close();
		etchState.setValue(newEtch);
	}

	@Override
	public void setupFrame(JFrame frame) {
		frame.getContentPane().setLayout(new MigLayout());
		frame.getContentPane().add(this,"dock center");
	}
}
