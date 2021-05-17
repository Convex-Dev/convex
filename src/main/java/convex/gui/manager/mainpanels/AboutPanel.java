package convex.gui.manager.mainpanels;

import java.awt.BorderLayout;

import javax.swing.JButton;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTextArea;

import convex.core.State;
import convex.core.data.prim.CVMLong;
import convex.core.util.Text;
import convex.gui.components.ActionPanel;
import convex.gui.manager.PeerManager;
import convex.gui.utils.Toolkit;

@SuppressWarnings("serial")
public class AboutPanel extends JPanel {

	private final JTextArea textArea;

	public AboutPanel() {
		setLayout(new BorderLayout(0, 0));

		JPanel panel = new ActionPanel();
		add(panel, BorderLayout.SOUTH);

		JButton creditsButton = new JButton("Credits");
		panel.add(creditsButton);

		JPanel panel_1 = new JPanel();
		add(panel_1, BorderLayout.CENTER);
		panel_1.setLayout(new BorderLayout(0, 0));

		textArea = new JTextArea();
		textArea.setEditable(false);
		textArea.setBackground(null);
		textArea.setFont(Toolkit.SMALL_MONO_FONT);

		PeerManager.getStateModel().addPropertyChangeListener(e -> {
			updateState((State) e.getNewValue());
		});

		panel_1.add(textArea);
		creditsButton.addActionListener(e -> {
			JOptionPane.showMessageDialog(null,
					"Icons made by Freepik from www.flaticon.com\n" + "Royalty free map image by J. Bruce Jones",
					"Credits", JOptionPane.PLAIN_MESSAGE);
		});

		updateState(PeerManager.getLatestState());
	}

	private String lpad(Object s) {
		return Text.leftPad(s.toString(), 25);
	}

	private void updateState(State s) {
		StringBuilder sb = new StringBuilder();
		CVMLong timestamp = s.getTimeStamp();

		sb.append("Consensus state hash: " + s.getHash().toHexString() + "\n");
		sb.append("Timestamp:            " + Text.dateFormat(timestamp.longValue()) + "   (" + timestamp + ")\n");
		sb.append("\n");
		sb.append("Max Blocks:           " + lpad(PeerManager.maxBlock) + "\n");
		sb.append("\n");
		sb.append("Account statistics\n");
		sb.append("  # Accounts:         " + lpad(s.getAccounts().count()) + "\n");
		sb.append("  # Peers:            " + lpad(s.getPeers().count()) + "\n");
		sb.append("\n");
		sb.append("Globals\n");
		sb.append("  fees:               " + lpad(Text.toFriendlyBalance(s.getGlobalFees().longValue())) + "\n");
		sb.append("  juice-price:        " + lpad(Text.toFriendlyBalance(s.getJuicePrice().longValue())) + "\n");
		sb.append("\n");
		sb.append("Total funds:          " + lpad(Text.toFriendlyBalance(s.computeTotalFunds())) + "\n");
		sb.append("Total stake:          " + lpad(Text.toFriendlyBalance(s.computeStakes().get(null))) + "\n");

		textArea.setText(sb.toString());
	}

}
