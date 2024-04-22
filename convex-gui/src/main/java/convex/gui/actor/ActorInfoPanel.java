package convex.gui.actor;

import java.awt.BorderLayout;
import java.awt.Dimension;

import javax.swing.JPanel;
import javax.swing.JTextArea;

import convex.core.State;
import convex.core.data.AccountStatus;
import convex.core.data.Address;
import convex.gui.components.models.StateModel;
import convex.gui.utils.Toolkit;

@SuppressWarnings("serial")
public class ActorInfoPanel extends JPanel {

	protected Address actor;
	protected JTextArea infoArea;

	public ActorInfoPanel(StateModel<State> manager, Address contract) {
		this.actor = contract;
		setLayout(new BorderLayout(0, 0));

		this.setPreferredSize(new Dimension(600, 400));

		infoArea = new JTextArea();
		add(infoArea, BorderLayout.CENTER);
		infoArea.setBackground(null);
		infoArea.setFont(Toolkit.SMALL_MONO_FONT);

		manager.addPropertyChangeListener(e -> {
			updateInfo((State) e.getNewValue());
		});
		updateInfo(manager.getValue());
	}

	private void updateInfo(State latestState) {
		StringBuilder sb = new StringBuilder();
		AccountStatus as = latestState.getAccount(actor);

		sb.append("Actor Address: " + actor.toHexString() + "\n");
		sb.append("Actor Balance: " + as.getBalance() + "\n");
		sb.append("\n");

		infoArea.setText(sb.toString());
	}

}
