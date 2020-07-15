package convex.gui.manager.windows.peer;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.text.DecimalFormat;
import java.text.NumberFormat;

import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;

import convex.core.Init;
import convex.core.State;
import convex.core.data.Address;
import convex.core.data.SignedData;
import convex.core.lang.Reader;
import convex.core.store.Stores;
import convex.core.transactions.ATransaction;
import convex.core.transactions.Invoke;
import convex.core.util.Utils;
import convex.gui.components.ActionPanel;
import convex.gui.components.PeerView;
import convex.gui.manager.PeerManager;
import convex.gui.manager.Toolkit;
import convex.net.Connection;
import convex.net.Message;
import convex.net.ResultConsumer;

@SuppressWarnings("serial")
public class StressPanel extends JPanel {

	protected PeerView peerView;

	private ActionPanel actionPanel;

	private JButton btnRun;

	private JSpinner transactionCountSpinner;
	private JSpinner opCountSpinner;

	public StressPanel(PeerView peerView) {
		this.peerView = peerView;
		this.setLayout(new BorderLayout());

		actionPanel = new ActionPanel();
		add(actionPanel, BorderLayout.SOUTH);

		btnRun = new JButton("Run Test");
		actionPanel.add(btnRun);
		btnRun.addActionListener(e -> {
			btnRun.setEnabled(false);
			SwingUtilities.invokeLater(() -> runStressTest());
		});

		splitPane = new JSplitPane();
		add(splitPane, BorderLayout.CENTER);

		JPanel panel = new JPanel();
		splitPane.setLeftComponent(panel);
		FlowLayout flowLayout = (FlowLayout) panel.getLayout();
		flowLayout.setAlignment(FlowLayout.LEFT);
		flowLayout.setAlignOnBaseline(true);

		// =========================================
		// Option Panel

		JPanel optionPanel = new JPanel();
		panel.add(optionPanel);
		optionPanel.setLayout(new GridLayout(0, 2, 0, 0));

		JLabel lblNewLabel = new JLabel("Transactions");
		optionPanel.add(lblNewLabel);
		transactionCountSpinner = new JSpinner();
		transactionCountSpinner.setModel(new SpinnerNumberModel(1000, 1, 1000000, 100));
		optionPanel.add(transactionCountSpinner);

		JLabel lblNewLabel2 = new JLabel("Ops per Transaction");
		optionPanel.add(lblNewLabel2);
		opCountSpinner = new JSpinner();
		opCountSpinner.setModel(new SpinnerNumberModel(1, 1, 1000, 10));
		optionPanel.add(opCountSpinner);

		// =========================================
		// Result Panel

		resultPanel = new JPanel();
		splitPane.setRightComponent(resultPanel);
		resultPanel.setLayout(new BorderLayout(0, 0));

		resultArea = new JTextArea();
		resultArea.setText("No results yet");
		resultArea.setLineWrap(true);
		resultArea.setEditable(false);
		resultPanel.add(resultArea);
		resultArea.setFont(Toolkit.SMALL_MONO_FONT);
	}

	long errors = 0;
	long results = 0;

	public final ResultConsumer resultHandler = new ResultConsumer() {
		@Override
		protected void handleResult(Object result) {
			results++;
		}

		@Override
		protected void handleError(Message m) {
			errors++;
		}
		
	};
	
	private JSplitPane splitPane;
	private JPanel resultPanel;
	private JTextArea resultArea;

	long startTime;
	long endTime;

	NumberFormat formatter = new DecimalFormat("#0.000");

	
	private synchronized void runStressTest() {
		errors = 0;
		results = 0;
		State startState = PeerManager.getLatestState();
		startTime = Utils.getCurrentTimestamp();
		try {
			InetSocketAddress sa = peerView.peerServer.getHostAddress();

			Connection pc = Connection.connect(sa, resultHandler, Stores.CLIENT_STORE);

			Address address = Init.HERO;
			int transCount = (Integer) transactionCountSpinner.getValue();
			int opCount = (Integer) opCountSpinner.getValue();
			
			new SwingWorker<String,Object>() {
				@Override
				protected String doInBackground() throws Exception {
					// Use client store
					// Stores.setCurrent(Stores.CLIENT_STORE);
					
					long seq = startState.getAccount(address).getSequence();
					for (int i = 0; i < transCount; i++) {
						StringBuilder sb = new StringBuilder();
						sb.append("(def a (do ");
						for (int j = 0; j < opCount; j++) {
							sb.append(" (* 10 " + seq + ")");
						}
						sb.append("))");
						String source = sb.toString();
						ATransaction t = Invoke.create(++seq, Reader.read(source));
						SignedData<ATransaction> signed = Init.HERO_KP.signData(t);
						long id = pc.sendTransaction(signed);
						while (id <= 0) {
							// loop if there is temporary blockage on sending messages
							Thread.sleep(0);
							if (pc.isClosed()) throw new Error("Cannot use Peer Connection: " + pc);
							id = pc.sendTransaction(signed);
						}
					}
					
					long sendTime = Utils.getCurrentTimestamp();

					while ((results < transCount) && (Utils.getCurrentTimestamp() < sendTime + 1000)) {
						Thread.sleep(1);
					}
					endTime = Utils.getCurrentTimestamp();
					
					Thread.sleep(100); // wait for state update to be reflected
					State endState = PeerManager.getLatestState();

					StringBuilder sb = new StringBuilder();
					sb.append("Results for " + transCount + " transactions\n");
					sb.append(results + " responses received\n");
					sb.append(errors + " errors received\n");
					sb.append("\n");
					sb.append("Send time:     " + formatter.format((sendTime - startTime) * 0.001) + "s\n");
					sb.append("End time:      " + formatter.format((endTime - startTime) * 0.001) + "s\n");
					sb.append("Consenus time: " + formatter.format((endState.getTimeStamp() - startTime) * 0.001) + "s\n");
				
					String report=sb.toString();
					return report;
				}
				
				@Override
				protected void done() {
					try {
						resultArea.setText(get());
					} catch (Exception e) {
						resultArea.setText(e.getMessage());
					}
				}
			}.execute();
			
		} catch (IOException e) {
			e.printStackTrace();
		} finally {
			btnRun.setEnabled(true);
		}
	}
}
