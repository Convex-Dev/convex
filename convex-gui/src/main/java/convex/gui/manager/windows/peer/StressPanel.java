package convex.gui.manager.windows.peer;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.text.DecimalFormat;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

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
import convex.core.Coin;
import convex.core.Result;
import convex.core.State;
import convex.core.crypto.AKeyPair;
import convex.core.data.Address;
import convex.core.data.Strings;
import convex.core.lang.Reader;
import convex.core.transactions.ATransaction;
import convex.core.transactions.Invoke;
import convex.core.util.Utils;
import convex.gui.components.ActionPanel;
import convex.gui.components.PeerView;
import convex.gui.manager.PeerGUI;
import convex.gui.utils.Toolkit;

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
		clientCountSpinner.setModel(new SpinnerNumberModel(1, 1, 100, 1));
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
	long values = 0;

	private JSplitPane splitPane;
	private JPanel resultPanel;
	private JTextArea resultArea;

	NumberFormat formatter = new DecimalFormat("#0.000");

	private synchronized void runStressTest() {
		errors = 0;
		values = 0;
		Address address=PeerGUI.getGenesisAddress();

		int transCount = (Integer) transactionCountSpinner.getValue();
		int opCount = (Integer) opCountSpinner.getValue();
		// TODO: enable multiple clients
		int clientCount = (Integer) clientCountSpinner.getValue();

		new SwingWorker<String,Object>() {
			@Override
			protected String doInBackground() throws Exception {
				StringBuilder sb = new StringBuilder();
				try {
					InetSocketAddress sa = peerView.peerServer.getHostAddress();

					// Use client store
					// Stores.setCurrent(Stores.CLIENT_STORE);
					ArrayList<CompletableFuture<Result>> frs=new ArrayList<>();
					Convex pc = Convex.connect(sa, address,PeerGUI.getUserKeyPair(0));
					
					ArrayList<Convex> ccs=new ArrayList<>(clientCount);
					for (int i=0; i<clientCount; i++) {
						AKeyPair kp=AKeyPair.generate();
						Address clientAddr = pc.createAccountSync(kp.getAccountKey());
						pc.transfer(clientAddr, Coin.DIAMOND);
						Convex cc=Convex.connect(sa,clientAddr,kp);
						ccs.add(cc);
					}
					// Make sure we are in consensus
					pc.transactSync(Invoke.create(address, -1, Strings.create("sync")));
					long startTime = Utils.getCurrentTimestamp();
					
					ArrayList<Future<Object>> cfutures=Utils.futureMap (cc->{
						try {
							for (int i = 0; i < transCount; i++) {
								StringBuilder tsb = new StringBuilder();
								tsb.append("(def a (do ");
								for (int j = 0; j < opCount; j++) {
									tsb.append(" (* 10 " + i + ")");
								}
								tsb.append("))");
								String source = tsb.toString();
								
								ATransaction t = Invoke.create(cc.getAddress(),-1, Reader.read(source));
								CompletableFuture<Result> fr;
									fr = cc.transact(t);
								frs.add(fr);
							}
						} catch (IOException e) {
							throw Utils.sneakyThrow(e);
						}
						return null;
					},ccs);
					// wait for everything to be sent
					for (int i=0; i<clientCount; i++) {
						cfutures.get(i).get(60, TimeUnit.SECONDS);
					}

					long sendTime = Utils.getCurrentTimestamp();

					List<Result> results = Utils.completeAll(frs).get(60, TimeUnit.SECONDS);
					long endTime = Utils.getCurrentTimestamp();

					for (Result r : results) {
						if (r.isError()) {
							errors++;
						} else {
							values++;
						}
					}
					
					for (int i=0; i<clientCount; i++) {
						ccs.get(i).close();
					}

					Thread.sleep(100); // wait for state update to be reflected
					State endState = PeerGUI.getLatestState();

					sb.append("Results for " + transCount + " transactions\n");
					sb.append(values + " values received\n");
					sb.append(errors + " errors received\n");
					sb.append("\n");
					sb.append("Send time:     " + formatter.format((sendTime - startTime) * 0.001) + "s\n");
					sb.append("End time:      " + formatter.format((endTime - startTime) * 0.001) + "s\n");
					sb.append("Consensus time: "
							+ formatter.format((endState.getTimeStamp().longValue() - startTime) * 0.001) + "s\n");

				} catch (IOException e) {
					e.printStackTrace();
				} finally {
					btnRun.setEnabled(true);
				}

				String report = sb.toString();
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
