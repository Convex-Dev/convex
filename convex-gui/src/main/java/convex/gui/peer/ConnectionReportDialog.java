package convex.gui.peer;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Font;
import java.net.InetSocketAddress;
import java.util.Map;

import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;

import convex.api.Convex;
import convex.api.ConvexLocal;
import convex.core.data.AccountKey;
import java.util.List;
import convex.gui.components.ActionButton;
import convex.gui.components.ActionPanel;
import convex.peer.ConnectionManager;
import convex.peer.Server;

/**
 * Diagnostic dialog showing inbound and outbound connections
 * for all peers, including trust status.
 */
@SuppressWarnings("serial")
public class ConnectionReportDialog extends JDialog {

	private final PeerGUI manager;
	private final JTextArea textArea;

	public ConnectionReportDialog(JFrame parent, PeerGUI manager) {
		super(parent, "Connection Report", false);
		this.manager = manager;

		setLayout(new BorderLayout());
		setPreferredSize(new Dimension(800, 600));

		textArea = new JTextArea();
		textArea.setEditable(false);
		textArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
		add(new JScrollPane(textArea), BorderLayout.CENTER);

		ActionPanel buttonPanel = new ActionPanel();
		buttonPanel.add(new ActionButton("Refresh", 0xe5d5, e -> refresh()));
		buttonPanel.add(new ActionButton("Close", 0xe5cd, e -> dispose()));
		add(buttonPanel, BorderLayout.SOUTH);

		refresh();

		pack();
		setLocationRelativeTo(parent);
	}

	private void refresh() {
		textArea.setText(buildReport());
		textArea.setCaretPosition(0);
	}

	private String buildReport() {
		StringBuilder sb = new StringBuilder();
		sb.append("=== Connection Report ===\n\n");

		List<ConvexLocal> peers = new java.util.ArrayList<>();
		var peerList = manager.getPeerList();
		for (int i = 0; i < peerList.getSize(); i++) {
			peers.add(peerList.getElementAt(i));
		}

		for (ConvexLocal cvl : peers) {
			Server server = cvl.getLocalServer();
			if (server == null) continue;

			AccountKey peerKey = server.getPeerKey();
			sb.append("Peer: 0x").append(peerKey.toHexString(8)).append("...");
			sb.append("  Port: ").append(server.getPort());
			if (!server.isLive()) {
				sb.append("  [INACTIVE]");
			}
			sb.append("\n");

			// Inbound
			int inboundCount = server.getInboundConnectionCount();
			long verified = server.getInboundVerifiedCount();
			int pendingVerify = server.getInboundPendingVerifications();
			sb.append("  Inbound:  ");
			if (inboundCount >= 0) {
				sb.append(inboundCount).append(" channels");
			} else {
				sb.append("n/a");
			}
			sb.append("  (verified: ").append(verified);
			if (pendingVerify > 0) {
				sb.append(", verifying: ").append(pendingVerify);
			}
			sb.append(")\n");

			// Outbound
			ConnectionManager cm = server.getConnectionManager();
			Map<AccountKey, Convex> outbound = cm.getConnections();
			sb.append("  Outbound: ").append(outbound.size()).append(" connections\n");

			for (Map.Entry<AccountKey, Convex> entry : outbound.entrySet()) {
				AccountKey key = entry.getKey();
				Convex conn = entry.getValue();
				sb.append("    -> 0x").append(key.toHexString(8)).append("...");

				// Connection type (ConvexLocal, ConvexRemote, etc.)
				sb.append("  ").append(conn.getClass().getSimpleName());

				InetSocketAddress addr = conn.getHostAddress();
				if (addr != null) {
					sb.append("  ").append(addr);
				}

				if (conn.isConnected()) {
					sb.append("  [connected]");
				} else {
					sb.append("  [disconnected]");
				}

				sb.append("\n");
			}

			sb.append("\n");
		}

		return sb.toString();
	}
}
