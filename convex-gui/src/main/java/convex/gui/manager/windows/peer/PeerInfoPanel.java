package convex.gui.manager.windows.peer;

import java.awt.BorderLayout;

import javax.swing.JButton;
import javax.swing.JPanel;
import javax.swing.JTextArea;

import convex.core.util.Text;
import convex.gui.components.ActionPanel;
import convex.gui.components.PeerView;
import convex.gui.utils.Toolkit;
import convex.peer.AThreadedComponent;
import convex.peer.Server;

@SuppressWarnings("serial")
public class PeerInfoPanel extends JPanel {

	private final JTextArea textArea;

	public PeerInfoPanel(PeerView p) {
		setLayout(new BorderLayout(0, 0));

		JPanel panel = new ActionPanel();
		add(panel, BorderLayout.SOUTH);

		JButton refreshButton = new JButton("Refresh");
		panel.add(refreshButton);

		JPanel panel_1 = new JPanel();
		add(panel_1, BorderLayout.CENTER);
		panel_1.setLayout(new BorderLayout(0, 0));

		textArea = new JTextArea();
		textArea.setEditable(false);
		textArea.setBackground(null);
		textArea.setFont(Toolkit.SMALL_MONO_FONT);

		panel_1.add(textArea);
		refreshButton.addActionListener(e -> {
			updateState(p);
		});

		updateState(p);
	}

	protected String lpad(Object s) {
		return Text.leftPad(s.toString(), 30);
	}

	private void updateState(PeerView p) {
		StringBuilder sb = new StringBuilder();
		Server s=p.peerServer;
		
		if (s==null) {
			sb.append("Not a local Peer");
		} else {
			sb.append("Running:              " + s.isLive() + "\n");
			sb.append("Key:                  " + s.getPeerKey() + "\n");
			sb.append("Address:              " + s.getHostAddress() + "\n");
			sb.append("\n");
	
			sb.append(s.getStatusVector()+"\n");
			sb.append("\n");
			
			sb.append("Transactions Pending: "+s.getTransactionHandler().countInterests());
			sb.append("\n");

			sb.append("Beliefs Sent:         "+s.getBeliefPropagator().getBeliefBroadcastCount()+"\n");
			sb.append("Beliefs Received:     "+s.getBeliefReceivedCount()+"\n");
			sb.append("\n");
			
			sb.append("Load averages\n");
			sb.append("Transaction handler:  "+load(s.getTransactionHandler())+"\n");
			sb.append("Query handler:        "+load(s.getQueryProcessor())+"\n");
			sb.append("Belief Propagator:    "+load(s.getBeliefPropagator())+"\n");
			sb.append("CVM Executor:         "+load(s.getCVMExecutor())+"\n");

		}
		
		textArea.setText(sb.toString());
	}
	

	private String load(AThreadedComponent  comp) {
		double ld=comp.getLoad();
		return Text.leftPad((long)(ld*100)+"%", 6);
	}

}
