package convex.gui.manager.windows.peer;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.text.DecimalFormat;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.concurrent.Future;

import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;

import convex.api.Convex;
import convex.core.Init;
import convex.core.Result;
import convex.core.State;
import convex.core.data.Address;
import convex.core.lang.Reader;
import convex.core.transactions.ATransaction;
import convex.core.transactions.Invoke;
import convex.core.util.Utils;
import convex.gui.components.ActionPanel;
import convex.gui.components.PeerView;
import convex.gui.manager.PeerManager;
import convex.gui.utils.Toolkit;
import convex.net.ResultConsumer;

@SuppressWarnings("serial")
public class StressPanel extends JPanel {

	protected PeerView peerView;

	private ActionPanel actionPanel;

	private JButton btnRun;

	private JSpinner transactionCountSpinner;
	private JSpinner opCountSpinner;
	private JSpinner clientCountSpinner;

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

		JLabel lblNewLabel = new JLabel("Transactions per client");
		optionPanel.add(lblNewLabel);
		transactionCountSpinner = new JSpinner();
		transactionCountSpinner.setModel(new SpinnerNumberModel(1000, 1, 1000000, 100));
		optionPanel.add(transactionCountSpinner);

		JLabel lblNewLabel2 = new JLabel("Ops per Transaction");
		optionPanel.add(lblNewLabel2);
		opCountSpinner = new JSpinner();
		opCountSpinner.setModel(new SpinnerNumberModel(1, 1, 1000, 10));
		optionPanel.add(opCountSpinner);
		
		JLabel lblNewLabel3 = new JLabel("Clients");
		optionPanel.add(lblNewLabel3);
		clientCountSpinner = new JSpinner();
		clientCountSpinner.setModel(new SpinnerNumberModel(1, 1, 1, 1));
		optionPanel.add(clientCountSpinner);


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
		protected void handleError(long id, Object code, Object msg) {
			errors++;
		}
		
	};
	
	private JSplitPane splitPane;
	private JPanel resultPanel;
	private JTextArea resultArea;

	NumberFormat formatter = new DecimalFormat("#0.000");

	
	private synchronized void runStressTest() {
		errors = 0;
		results = 0;
			Address address=Init.HERO;

			int transCount = (Integer) transactionCountSpinner.getValue();
			int opCount = (Integer) opCountSpinner.getValue();
			// TODO: enable multiple clients
			// int clientCount = (Integer) opCountSpinner.getValue();
			
			new SwingWorker<String,Object>() {
				@Override
				protected String doInBackground() throws Exception {
					StringBuilder sb = new StringBuilder();
					
					try {


					InetSocketAddress sa = peerView.peerServer.getHostAddress();
					long startTime = Utils.getCurrentTimestamp();

					// Use client store
					// Stores.setCurrent(Stores.CLIENT_STORE);
					ArrayList<Future<Result>> frs=new ArrayList<>();
					Convex pc = Convex.connect(sa, address,Init.HERO_KP);
					
					for (int i = 0; i < transCount; i++) {
						StringBuilder tsb = new StringBuilder();
						tsb.append("(def a (do ");
						for (int j = 0; j < opCount; j++) {
							tsb.append(" (* 10 " + i + ")");
						}
						tsb.append("))");
						String source = tsb.toString();
						ATransaction t = Invoke.create(Init.HERO,-1, Reader.read(source));
						Future<Result> fr = pc.transact(t);
						frs.add(fr);
					}
					
					long sendTime = Utils.getCurrentTimestamp();

					while ((results < transCount) && (Utils.getCurrentTimestamp() < sendTime + 1000)) {
						Thread.sleep(1);
					}
					long endTime = Utils.getCurrentTimestamp();
					
					Thread.sleep(100); // wait for state update to be reflected
					State endState = PeerManager.getLatestState();

					
					sb.append("Results for " + transCount + " transactions\n");
					sb.append(results + " responses received\n");
					sb.append(errors + " errors received\n");
					sb.append("\n");
					sb.append("Send time:     " + formatter.format((sendTime - startTime) * 0.001) + "s\n");
					sb.append("End time:      " + formatter.format((endTime - startTime) * 0.001) + "s\n");
					sb.append("Consensus time: " + formatter.format((endState.getTimeStamp().longValue() - startTime) * 0.001) + "s\n");
				
					} catch (IOException e) {
						e.printStackTrace();
					} finally {
						btnRun.setEnabled(true);
					}
					
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
			

	}
}
