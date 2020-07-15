package convex.gui.manager.windows.actor;

import java.awt.BorderLayout;
import java.awt.Dimension;

import javax.swing.JPanel;
import javax.swing.JTextArea;

import convex.core.State;
import convex.core.data.AVector;
import convex.core.data.AccountStatus;
import convex.core.data.Address;
import convex.core.util.Utils;
import convex.gui.manager.PeerManager;
import convex.gui.manager.Toolkit;

/**
 * A panel displaying the compiled code for a smart contract
 */
@SuppressWarnings("serial")
public class ActorCodePanel extends JPanel {

	protected PeerManager manager;
	protected Address contract;
	protected JTextArea infoArea;

	public ActorCodePanel(PeerManager manager, Address contract) {
		this.manager = manager;
		this.contract = contract;
		setLayout(new BorderLayout(0, 0));

		this.setPreferredSize(new Dimension(600, 400));

		infoArea = new JTextArea();
		add(infoArea, BorderLayout.CENTER);
		infoArea.setBackground(null);
		infoArea.setFont(Toolkit.SMALL_MONO_FONT);

		updateInfo(PeerManager.getLatestState());
	}

	private void updateInfo(State latestState) {
		StringBuilder sb = new StringBuilder();
		AccountStatus as = latestState.getAccount(contract);

		try {
			AVector<Object> v = as.getActorArgs();
			sb.append(Utils.ednString(v.get(0)));
			sb.append("\n");
			sb.append("\n");

			ActorInfoPanel.addInitParams(sb, v);
		} catch (Exception e) {
			sb.append(e.getMessage());
		}

		infoArea.setText(sb.toString());
	}

}
