package convex.gui.server;

import java.awt.BorderLayout;
import java.awt.EventQueue;

import javax.swing.JButton;
import javax.swing.JPanel;
import javax.swing.JScrollPane;

import convex.api.Convex;
import convex.core.text.Text;
import convex.gui.components.ActionPanel;
import convex.gui.components.CodeLabel;
import convex.gui.components.NonUpdatingCaret;
import convex.gui.utils.Refresh;
import convex.gui.utils.Toolkit;
import convex.peer.AThreadedComponent;
import convex.peer.ConnectionManager;
import convex.peer.Server;

@SuppressWarnings("serial")
public class PeerInfoPanel extends JPanel {

	private final CodeLabel textArea;

	public PeerInfoPanel(Convex p) {
		setLayout(new BorderLayout(0, 0));

		JPanel panel = new ActionPanel();
		add(panel, BorderLayout.SOUTH);

		JButton refreshButton = new JButton("Refresh");
		panel.add(refreshButton);

		textArea = new CodeLabel();
		textArea.setFont(Toolkit.MONO_FONT);
		textArea.setEditable(false);
		textArea.setLineWrap(true);
		textArea.setWrapStyleWord(true);
		textArea.setBackground(null);
		textArea.setCaret(new NonUpdatingCaret());
		textArea.setColumns(100);
		textArea.setRows(40);
		textArea.setMaxColumns(100);
		
		JScrollPane jsp1=new JScrollPane(textArea);
		add(jsp1, BorderLayout.CENTER);
		
		Refresh.whileShowing(this, 500, ()->updateState(p));

		refreshButton.addActionListener(e -> {
			updateState(p);
		});
	}

	protected String lpad(Object s) {
		return Text.leftPad(s.toString(), 30);
	}

	/** Gathers the peer's status and shows it. Safe from any thread. */
	private void updateState(Convex p) {
		StringBuilder sb = new StringBuilder();
		Server s=p.getLocalServer();
		
		if (s==null) {
			sb.append("Not a local Peer");
		} else {
			sb.append("Running:              " + s.isLive() + "\n");
			sb.append("Key:                  " + s.getPeerKey() + "\n");
			sb.append("Address:              " + s.getHostAddress() + "\n");
			sb.append("Store:                " + s.getStore() + "\n");
			sb.append("\n");
	
			sb.append(s.getStatusData()+"\n");
			sb.append("\n");
			
			sb.append("Transactions:\n");
			sb.append("- Received:           "+s.getTransactionHandler().receivedTransactionCount+"\n");
			sb.append("- Queued (valid):     "+s.getTransactionHandler().clientTransactionCount+"\n");
			sb.append("- Pending:            "+s.getTransactionHandler().countInterests()+"\n");
			sb.append("\n");

			sb.append("Beliefs Sent:         "+s.getBeliefPropagator().getBeliefBroadcastCount()+"\n");
			sb.append("Beliefs Received:     "+s.getBeliefReceivedCount()+"\n");
			sb.append("\n");
			
			sb.append("Load averages:\n");
			sb.append("- Transaction handler:  "+load(s.getTransactionHandler())+"\n");
			sb.append("- Query handler:        "+load(s.getQueryProcessor())+"\n");
			sb.append("- Belief Propagator:    "+load(s.getBeliefPropagator())+"\n");
			sb.append("- CVM Executor:         "+load(s.getCVMExecutor())+"\n");
			sb.append("- Connection Manager:   "+load(s.getConnectionManager())+"\n");
		}

		String status=sb.toString();
		EventQueue.invokeLater(()->textArea.setText(status));
	}
	

	private String load(AThreadedComponent  comp) {
		return loadPct(comp.getLoad());
	}

	private String load(ConnectionManager cm) {
		return loadPct(cm.getLoad());
	}

	private String loadPct(double ld) {
		return Text.leftPad((long)(ld*100)+"%", 6);
	}

}
