package convex.gui.server;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.text.DecimalFormat;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeoutException;

import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingWorker;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.api.Convex;
import convex.core.Networks;
import convex.core.Result;
import convex.core.crypto.AKeyPair;
import convex.core.cvm.transactions.ATransaction;
import convex.core.cvm.transactions.Invoke;
import convex.core.cvm.transactions.Multi;
import convex.core.cvm.transactions.Transfer;
import convex.core.data.ACell;
import convex.core.data.AVector;
import convex.core.data.Hash;
import convex.core.data.SignedData;
import convex.core.cvm.Address;
import convex.core.data.Strings;
import convex.core.lang.Reader;
import convex.core.text.Text;
import convex.core.util.ThreadUtils;
import convex.core.util.Utils;
import convex.gui.components.ActionPanel;
import convex.gui.utils.Toolkit;
import convex.peer.Server;

@SuppressWarnings("serial")
public class StressPanel extends JPanel {
	static final Logger log = LoggerFactory.getLogger(StressPanel.class.getName());

	protected Convex peerConvex;

	private ActionPanel actionPanel;

	private JButton btnRun;

	private JSpinner requestCountSpinner;
	private JSpinner transactionCountSpinner;
	private JSpinner opCountSpinner;
	private JSpinner clientCountSpinner;
	private JSpinner repeatTimeSpinner;
	private JCheckBox syncCheckBox;
	private JCheckBox distCheckBox;
	private JCheckBox repeatCheckBox;
	private JCheckBox queryCheckBox;
	private JCheckBox localCheckBox;
	private JCheckBox preCompileCheckBox;

	private JSplitPane splitPane;
	private JPanel resultPanel;
	private JTextArea resultArea;
	
	private JComboBox<String> txTypeBox;

	public StressPanel(Convex peerView) {
		this.peerConvex = peerView;
		this.setLayout(new BorderLayout());

		actionPanel = new ActionPanel();
		add(actionPanel, BorderLayout.SOUTH);

		btnRun = new JButton("Run Test");
		actionPanel.add(btnRun);
		btnRun.addActionListener(e -> {
			Hash network=peerView.getLocalServer().getPeer().getGenesisHash();
			if (network.equals(Networks.PROTONET_GENESIS)) {
				int confirm=JOptionPane.showConfirmDialog(this, "This is the live network. Running a stress test is likley to be expensive! Are you really sure you want to do this?", "Run test on Live network?", JOptionPane.WARNING_MESSAGE);
				if (confirm!=JOptionPane.OK_OPTION) return;
			}
			
			btnRun.setEnabled(false);
			Address address=peerConvex.getAddress();
			AKeyPair kp=peerConvex.getKeyPair();
			new StressTest(kp, address).execute();
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

		JLabel lblClients = new JLabel("Clients");
		optionPanel.add(lblClients);
		clientCountSpinner = new JSpinner();
		// Note: about 300 max number of clients before hitting juice limits for account creation
		clientCountSpinner.setModel(new SpinnerNumberModel(100, 1, 1000, 1));
		optionPanel.add(clientCountSpinner);

		JLabel lblRequests = new JLabel("Requests per client");
		optionPanel.add(lblRequests);
		requestCountSpinner = new JSpinner();
		requestCountSpinner.setModel(new SpinnerNumberModel(100, 1, 1000000, 10));
		optionPanel.add(requestCountSpinner);

		JLabel lblTrans = new JLabel("Transactions per Request");
		optionPanel.add(lblTrans);
		transactionCountSpinner = new JSpinner();
		transactionCountSpinner.setModel(new SpinnerNumberModel(10, 1, 1000, 1));
		optionPanel.add(transactionCountSpinner);

		JLabel lblOps = new JLabel("Ops per Transaction");
		optionPanel.add(lblOps);
		opCountSpinner = new JSpinner();
		opCountSpinner.setModel(new SpinnerNumberModel(1, 1, 1000, 10));
		optionPanel.add(opCountSpinner);
		
		JLabel lblSync=new JLabel("Sync Requests?");
		optionPanel.add(lblSync);
		syncCheckBox=new JCheckBox();
		optionPanel.add(syncCheckBox);
		syncCheckBox.setSelected(false);
		
		//JLabel lblDist=new JLabel("Distribute over Peers?");
		//optionPanel.add(lblDist);
		distCheckBox=new JCheckBox();
		//optionPanel.add(distCheckBox);
		distCheckBox.setSelected(false);
		
		optionPanel.add(new JLabel("Repeat requests?"));
		repeatCheckBox=new JCheckBox();
		optionPanel.add(repeatCheckBox);
		repeatCheckBox.setSelected(false);
		
		optionPanel.add(new JLabel("Query"));
		queryCheckBox=new JCheckBox();
		optionPanel.add(queryCheckBox);
		queryCheckBox.setSelected(false);

		optionPanel.add(new JLabel("Local (in-JVM)"));
		localCheckBox=new JCheckBox();
		optionPanel.add(localCheckBox);
		localCheckBox.setSelected(false);

		optionPanel.add(new JLabel("Pre-compile"));
		preCompileCheckBox=new JCheckBox();
		optionPanel.add(preCompileCheckBox);
		preCompileCheckBox.setSelected(false);

		optionPanel.add(new JLabel("Repeat timeout"));
		repeatTimeSpinner = new JSpinner();
		repeatTimeSpinner.setModel(new SpinnerNumberModel(60, 0, 3600, 1));
		optionPanel.add(repeatTimeSpinner);


		JLabel lblTxType=new JLabel("Transaction Type");
		txTypeBox=new JComboBox<String>();
		txTypeBox.addItem("Transfer");
		txTypeBox.addItem("Define Data");
		// txTypeBox.addItem("AMM Trade");
		txTypeBox.addItem("Invoke Const");
		txTypeBox.addItem("Actor Call");
		optionPanel.add(lblTxType);
		optionPanel.add(txTypeBox);


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
		resultArea.setFont(Toolkit.MONO_FONT);
	}

	NumberFormat formatter = new DecimalFormat("#0.000");

	private final class StressTest extends SwingWorker<String, Object> {
		long errors = 0;
		long values = 0;
		private final AKeyPair kp;
		private final Address address;
		int transCount = (Integer) transactionCountSpinner.getValue();
		int requestCount = (Integer) requestCountSpinner.getValue();
		int opCount = (Integer) opCountSpinner.getValue();
		int clientCount = (Integer) clientCountSpinner.getValue();
		String type=(String) txTypeBox.getSelectedItem();
		ArrayList<AKeyPair> kps=new ArrayList<>(clientCount);
		ArrayList<Convex> clients=new ArrayList<>(clientCount);
		InetSocketAddress sa = peerConvex.getHostAddress();

		private StressTest(AKeyPair kp, Address address) {
			this.kp = kp;
			this.address = address;
		}
		
		@Override
		protected String doInBackground() {
			String result=null;
			try {
				boolean running=true;
				while (running)  {
					result=doStressRun();
					running=repeatCheckBox.isSelected();
					if (running) Thread.sleep(((Integer)(repeatTimeSpinner.getValue()))*1000);
				};
			} catch (ExecutionException e) {
				log.info("Stress test worker terminated",e);
				resultArea.setText("Test Error: "+e);
			} catch (Exception e) {
				log.warn("Stress test worker terminated unexpectedly",e);
				resultArea.setText("Test Error: "+e);
			} finally {
				btnRun.setEnabled(true);
			}
			return result;
		}

		protected String doStressRun() throws Exception {
			StringBuilder sb = new StringBuilder();

			// Reset state for this run
			kps.clear();
			clients.clear();
			errors = 0;
			values = 0;

			resultArea.setText("Connecting clients...");
			boolean isLocal = localCheckBox.isSelected();
			Server server = peerConvex.getLocalServer();

			Convex pc;
			if (isLocal && server != null) {
				pc = Convex.connect(server, address, kp);
			} else {
				pc = Convex.connect(sa, address, kp);
			}

			// Generate client accounts
			StringBuilder cmdsb=new StringBuilder();
			cmdsb.append("(let [f (fn [k] (let [a (deploy `(do (set-key ~k) (set-controller #13)))] (transfer a 1000000000) a))] ");
			cmdsb.append("  (mapv f [");
			for (int i=0; i<clientCount; i++) {
				AKeyPair kp=AKeyPair.generate();
				kps.add(kp);
				cmdsb.append(" "+kp.getAccountKey());
			}
			cmdsb.append("]))");
			
			Result ccr=pc.transactSync(Invoke.create(address, ATransaction.UNKNOWN_SEQUENCE, cmdsb.toString()));
			if (ccr.isError()) throw new Error("Creating accounts failed: "+ccr);
			AVector<Address> clientAddresses=ccr.getValue();

			connectClients(clientAddresses);
			setupClients();

			// Pre-build transactions
			resultArea.setText("Building transactions...");
			ATransaction[][] allTransactions = new ATransaction[clientCount][requestCount];
			for (int c = 0; c < clientCount; c++) {
				Address origin = clients.get(c).getAddress();
				for (int i = 0; i < requestCount; i++) {
					allTransactions[c][i] = buildTransaction(origin, i);
				}
			}

			// Pre-compile Invoke commands if enabled
			if (preCompileCheckBox.isSelected() && !type.equals("Transfer")) {
				resultArea.setText("Compiling...");
				HashMap<ACell, ACell> compileCache = new HashMap<>();
				for (int c = 0; c < clientCount; c++) {
					for (int i = 0; i < requestCount; i++) {
						ATransaction t = allTransactions[c][i];
						if (t instanceof Invoke inv) {
							ACell code = inv.getCommand();
							ACell compiled = compileCache.computeIfAbsent(code, k -> {
								try {
									Result r = pc.preCompile(k).get();
									return r.isError() ? k : r.getValue();
								} catch (Exception e) {
									return k;
								}
							});
							if (compiled != code) {
								allTransactions[c][i] = Invoke.create(t.getOrigin(), t.getSequence(), compiled);
							}
						}
					}
				}
			}

			// Pre-sign transactions (skipped for query mode)
			boolean isQuery = queryCheckBox.isSelected();
			SignedData<ATransaction>[][] signedTransactions = null;
			if (!isQuery) {
				resultArea.setText("Signing transactions...");
				@SuppressWarnings("unchecked")
				SignedData<ATransaction>[][] signed = new SignedData[clientCount][requestCount];
				signedTransactions = signed;
				for (int c = 0; c < clientCount; c++) {
					Convex cc = clients.get(c);
					for (int i = 0; i < requestCount; i++) {
						signed[c][i] = cc.prepareTransaction(allTransactions[c][i]);
					}
				}
			}

			resultArea.setText("Syncing...");
			// Make sure we are in consensus
			pc.transactSync(Invoke.create(address, ATransaction.UNKNOWN_SEQUENCE, Strings.create("sync")));
			long startTime = Utils.getCurrentTimestamp();

			resultArea.setText("Sending transactions...");

			// Per-client futures — no synchronisation needed
			@SuppressWarnings("unchecked")
			CompletableFuture<Result>[][] clientFutures = new CompletableFuture[clientCount][requestCount];

			final SignedData<ATransaction>[][] finalSigned = signedTransactions;
			boolean isSyncMode = syncCheckBox.isSelected();

			ExecutorService ex = ThreadUtils.getVirtualExecutor();
			ArrayList<Integer> indices = new ArrayList<>(clientCount);
			for (int i = 0; i < clientCount; i++) indices.add(i);

			ArrayList<CompletableFuture<Object>> cfutures = ThreadUtils.futureMap(ex, idx -> {
				try {
					Convex cc = clients.get(idx);
					for (int i = 0; i < requestCount; i++) {
						CompletableFuture<Result> fr;
						if (isQuery) {
							if (isSyncMode) {
								Result r = cc.querySync(allTransactions[idx][i]);
								fr = CompletableFuture.completedFuture(r);
							} else {
								fr = cc.query(allTransactions[idx][i]);
							}
						} else {
							if (isSyncMode) {
								Result r = cc.transactSync(finalSigned[idx][i]);
								fr = CompletableFuture.completedFuture(r);
							} else {
								fr = cc.transact(finalSigned[idx][i]);
							}
						}
						clientFutures[idx][i] = fr;
					}
				} catch (Exception e) {
					throw Utils.sneakyThrow(e);
				}
				return null;
			}, indices);

			// Wait for all sends to complete
			for (int i = 0; i < clientCount; i++) {
				cfutures.get(i).get();
			}
			long sendTime = Utils.getCurrentTimestamp();

			// Flatten per-client futures
			ArrayList<CompletableFuture<Result>> frs = new ArrayList<>(clientCount * requestCount);
			for (int c = 0; c < clientCount; c++) {
				for (int i = 0; i < requestCount; i++) {
					frs.add(clientFutures[c][i]);
				}
			}

			int futureCount = frs.size();
			resultArea.setText("Awaiting " + futureCount + " results...");

			List<Result> results = ThreadUtils.completeAll(frs).get();
			long endTime = Utils.getCurrentTimestamp();

			HashMap<ACell, Integer> errorMap=new HashMap<>();
			for (Result r : results) {
				if (r.isError()) {
					errors++;
					Utils.histogramAdd(errorMap,r.getErrorCode());
				} else {
					values++;
				}
			}
			
			for (int i=0; i<clientCount; i++) {
				clients.get(i).close();
			}

			Thread.sleep(100); // wait for state update to be reflected

			long totalCount=clientCount*transCount*requestCount;
			sb.append("Results for " + Text.toFriendlyNumber(totalCount) + " transactions\n");
			sb.append(values + " values received\n");
			sb.append(errors + " errors received\n");
			if (errors>0) {
				sb.append(errorMap);
				sb.append("\n");
			}

			double sendTimeSec=(sendTime - startTime) * 0.001;
			double totalTime=(endTime - startTime) * 0.001;
			sb.append("\n");
			sb.append("Mode:           " + (isLocal ? "Local (in-JVM)" : "Remote (network)") + "\n");
			sb.append("Send time:      " + formatter.format(sendTimeSec) + "s\n");
			sb.append("Total time:     " + formatter.format(totalTime) + "s\n");
			sb.append("\n");

			sb.append("Approx TPS:     " + Text.toFriendlyIntString(totalCount/totalTime) + "\n");
			sb.append("Approx OPS:     " + Text.toFriendlyIntString(opCount*totalCount/totalTime) + "\n");

			String report = sb.toString();
			return report;
		}

		private void setupClients() throws IOException, TimeoutException {
			for (Convex c: clients) {
				String code=null;
				switch (type) {
					case "AMM Trade": code="nil"; break; 
					default: break;
				}
				if (code!=null) c.transact(code);
			}
		}

		protected void connectClients(AVector<Address> clientAddresses) throws IOException, TimeoutException, InterruptedException {
			Server server = peerConvex.getLocalServer();
			for (int i=0; i<clientCount; i++) {
				AKeyPair kp=kps.get(i);
				Address clientAddr = clientAddresses.get(i);
				Convex cc;
				if (localCheckBox.isSelected() && server != null) {
					cc=Convex.connect(server,clientAddr,kp);
				} else {
					cc=Convex.connect(sa,clientAddr,kp);
				}
				clients.add(cc);
			}
		}

		protected ATransaction buildTransaction(Address origin, int reqNo) {
			ATransaction[] trxs=new ATransaction[transCount];
			for (int k=0; k<transCount; k++) {
					trxs[k]=buildSubTransaction(reqNo, k, origin);
			}
			ATransaction t;
			if (transCount!=1) {
				t=Multi.create(origin, ATransaction.UNKNOWN_SEQUENCE, Multi.MODE_ANY, trxs);
			} else {
				t=trxs[0];
			}
			return t;
		}

		protected ATransaction buildSubTransaction(int reqNo, int txNo, Address origin) {
			Address target=clients.get((1+reqNo+txNo*6969)%clients.size()).getAddress();
			if (type.equals("Transfer")) {
				ATransaction t = Transfer.create(origin,ATransaction.UNKNOWN_SEQUENCE, target, 100);
				return t;
			}
			
			StringBuilder tsb = new StringBuilder();
			if (opCount>1) tsb.append("(loop [i 0] ");
			for (int j = 0; j < opCount; j++) {
				switch(type) {
					case "Define Data": tsb.append("(def a"+txNo+" "+reqNo+") "); break;
					case "Invoke Const": tsb.append("nil "); break;
					case "Actor Call": tsb.append("(call #9 (lookup *address*)) "); break;
					default: throw new Error("Bad TX type: "+type);
				}
			}
			if (opCount>1) tsb.append(" (cond (> i "+opCount+") nil (recur (inc i))))");
			String source = tsb.toString();
			ATransaction t = Invoke.create(origin,ATransaction.UNKNOWN_SEQUENCE, Reader.read(source));
			return t;
		}

		@Override
		protected void done() {
			try {
				resultArea.setText(get());
			} catch (Exception e) {
				resultArea.setText(e.getMessage());
			}
		}
	}

	
}
