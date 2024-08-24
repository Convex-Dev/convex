package convex.gui.state;

import java.awt.EventQueue;
import java.util.List;

import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;

import convex.core.crypto.AKeyPair;
import convex.core.data.ACell;
import convex.core.data.Cells;
import convex.core.init.Init;
import convex.core.lang.RT;
import convex.core.util.Utils;
import convex.gui.components.AbstractGUI;
import convex.gui.components.CodeLabel;
import convex.gui.utils.Toolkit;
import net.miginfocom.swing.MigLayout;

@SuppressWarnings("serial")
public class StateExplorer extends AbstractGUI {

	JTabbedPane tabbedPane = new JTabbedPane(JTabbedPane.TOP);
	protected ACell state;

	public StateExplorer(ACell state) {
		super ("State Explorer");
		this.state=state;
		this.setLayout(new MigLayout());

		StringBuilder sb=new StringBuilder();
		sb.append("HASH: "+Cells.getHash(state)+"\n");
		sb.append("TYPE: "+Utils.getClass(state).getSimpleName()+"\n");
		add(new CodeLabel(sb.toString()),"dock north");
		
		tabbedPane.addTab("Tree", null, new StateTreePanel(state), null);
		tabbedPane.addTab("Text", null, createTextPanel(state), null);
		tabbedPane.addTab("Encoding", null, new CodeLabel(Cells.getEncoding(state).toHexString()), null);
		add(tabbedPane, "dock center");
		
	}

	protected JComponent createTextPanel(ACell state) {
		JPanel panel=new JPanel();
		panel.setLayout(new MigLayout());
		panel.add(new JScrollPane(new CodeLabel(RT.toString(state,10000))),"dock center");
		return panel;
	}
	
	public static void explore (ACell a) {
		EventQueue.invokeLater(()->{
			new StateExplorer(a).run();
		});
	}

	public static void main(String[] args) {
		Toolkit.init();
		AKeyPair kp=AKeyPair.createSeeded(564646);
		ACell state=Init.createState(List.of(kp.getAccountKey()));
		
		StateExplorer gui=new StateExplorer(state);
		gui.run();
		gui.waitForClose();
		System.exit(0);
	}
}
