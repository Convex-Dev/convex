package convex.gui.peer.windows.state;

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
import convex.gui.components.AbstractGUI;
import convex.gui.components.CodeLabel;
import convex.gui.utils.Toolkit;
import net.miginfocom.swing.MigLayout;

@SuppressWarnings("serial")
public class StateWindow extends AbstractGUI {

	JTabbedPane tabbedPane = new JTabbedPane(JTabbedPane.TOP);
	private final ACell state;

	public StateWindow(ACell state) {
		this.state=state;
		this.setLayout(new MigLayout());
		add(tabbedPane, "dock center");

		tabbedPane.addTab("Tree", null, new StateTreePanel(state), null);
		tabbedPane.addTab("Text", null, createTextPanel(state), null);

	}

	protected JComponent createTextPanel(ACell state) {
		JPanel panel=new JPanel();
		panel.setLayout(new MigLayout());
		panel.add(new JScrollPane(new CodeLabel(RT.toString(state,10000))),"dock center");
		return panel;
	}

	@Override
	public String getTitle() {
		return "State explorer: "+Cells.getHash(state);
	}

	public static void main(String[] args) {
		Toolkit.init();
		AKeyPair kp=AKeyPair.createSeeded(564646);
		ACell state=Init.createBaseState(List.of(kp.getAccountKey()));
		new StateWindow(state).run();
	}
}
