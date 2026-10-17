package convex.gui.peer;

import java.awt.BorderLayout;

import javax.swing.JButton;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTextArea;

import convex.api.ConvexLocal;
import convex.core.cpos.Order;
import convex.core.cvm.Peer;
import convex.core.cvm.State;
import convex.core.crypto.bc.BCKeyPair;
import convex.core.crypto.bc.BCProvider;
import convex.core.data.prim.CVMLong;
import convex.core.text.Text;
import convex.core.util.Counters;
import convex.gui.components.ActionPanel;
import convex.gui.utils.Toolkit;

/**
 * Panel for diagnostic information about a Local Peer
 */
@SuppressWarnings("serial")
public class AboutPanel extends JPanel implements AutoCloseable {

	private final JTextArea textArea;
	protected ConvexLocal convex;

	/** Updates from the peer's server, stopped on close */
	private final PeerStateUpdates updates;

	public AboutPanel(ConvexLocal convex) {
		this.convex=convex;
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
		textArea.setFont(Toolkit.MONO_FONT);

		panel_1.add(textArea);
		creditsButton.addActionListener(e -> {
			JOptionPane.showMessageDialog(null,
					"Icons made by Freepik from www.flaticon.com\n" + "Royalty free map image by J. Bruce Jones",
					"Credits", JOptionPane.PLAIN_MESSAGE);
		});

		updateState(convex);
		updates=new PeerStateUpdates(convex.getLocalServer(),()->updateState(convex));
	}

	/** Stops following the peer's server. Called when the owning window closes. */
	@Override
	public void close() {
		updates.close();
	}

	private String lpad(Object s) {
		return Text.leftPad(s.toString(), 30);
	}

	private void updateState(ConvexLocal peer) {
		Peer p=peer.getLocalServer().getPeer();
		State s=p.getConsensusState();
		Order order=p.getPeerOrder();
		StringBuilder sb = new StringBuilder();
		CVMLong timestamp = s.getTimestamp();
		
		sb.append("Testnet info\n\n");

		sb.append("Consensus state hash: " + s.getHash().toHexString() + "\n");
		sb.append("Timestamp:            " + Text.dateFormat(timestamp.longValue()) + "   (" + timestamp + ")\n");
		sb.append("\n");
		sb.append("Block Count:          " + lpad((order==null)?0:order.getBlockCount()) + "\n");
		sb.append("\n");
		sb.append("Account statistics\n");
		sb.append("  # Accounts:         " + lpad(s.getAccounts().count()) + "\n");
		sb.append("  # Peers:            " + lpad(s.getPeers().count()) + "\n");
		sb.append("\n");
		sb.append("Globals\n");
		sb.append("  fees:               " + lpad(Text.toFriendlyNumber(s.getGlobalFees().longValue())) + "\n");
		sb.append("  juice-price:        " + lpad(Text.toFriendlyNumber(s.getJuicePrice().longValue())) + "\n");
		sb.append("\n");
		sb.append("Total funds:          " + lpad(Text.toFriendlyNumber(s.computeTotalBalance())) + "\n");
		sb.append("Total stake:          " + lpad(Text.toFriendlyIntString(s.computeStakes().get(null))) + "\n");
		sb.append("\n");
		sb.append("BC Signatures:        " + lpad(BCKeyPair.signatureCount + "\n"));
		sb.append("BC Verifications:     " + lpad(BCProvider.verificationCount + "\n"));
		sb.append("\n");
		sb.append(Counters.getStats());

		textArea.setText(sb.toString());
	}

}
