package convex.gui.manager.windows.actor;

import java.awt.BorderLayout;
import java.awt.Dimension;

import javax.swing.JPanel;
import javax.swing.JTextArea;

import convex.core.State;
import convex.core.data.AVector;
import convex.core.data.AccountStatus;
import convex.core.data.Address;
import convex.core.data.Syntax;
import convex.core.lang.Fn;
import convex.gui.manager.PeerManager;
import convex.gui.manager.Toolkit;

@SuppressWarnings("serial")
public class ActorInfoPanel extends JPanel {

	protected PeerManager manager;
	protected Address actor;
	protected JTextArea infoArea;

	public ActorInfoPanel(PeerManager manager, Address contract) {
		this.manager = manager;
		this.actor = contract;
		setLayout(new BorderLayout(0, 0));

		this.setPreferredSize(new Dimension(600, 400));

		infoArea = new JTextArea();
		add(infoArea, BorderLayout.CENTER);
		infoArea.setBackground(null);
		infoArea.setFont(Toolkit.SMALL_MONO_FONT);

		PeerManager.getStateModel().addPropertyChangeListener(e -> {
			updateInfo((State) e.getNewValue());
		});
		updateInfo(PeerManager.getLatestState());
	}

	private void updateInfo(State latestState) {
		StringBuilder sb = new StringBuilder();
		AccountStatus as = latestState.getAccount(actor);

		AVector<Object> v = as.getActorArgs();

		sb.append("Actor Address: " + actor.toHexString() + "\n");
		sb.append("Actor Balance: " + as.getBalance().toFriendlyString() + "\n");
		sb.append("\n");

		addInitParams(sb, v);

		infoArea.setText(sb.toString());
	}

	static void addInitParams(StringBuilder sb, AVector<Object> v) {
		sb.append("Initialisation parameters:\n");
		AVector<Syntax> params = ((Fn<?>) v.get(0)).getParams();
		int pc = params.size();
		for (int i = 0; i < pc; i++) {
			sb.append("  " + params.get(i).getValue() + " = " + v.get(i + 1) + "\n");
		}
		if (pc == 0) sb.append("  <no parameters>");
	}

}
