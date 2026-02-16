package convex.gui.server;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import javax.swing.BorderFactory;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSlider;
import javax.swing.JToggleButton;
import javax.swing.SwingConstants;

import convex.api.ConvexLocal;
import convex.core.Result;
import convex.core.crypto.AKeyPair;
import convex.core.cvm.Address;
import convex.core.cvm.Context;
import convex.core.data.ACell;
import convex.core.lang.Reader;
import convex.core.text.Text;
import convex.gui.utils.Toolkit;
import convex.peer.Server; 

/**
 * Live query load simulator. Each instance runs independently with configurable
 * client count, query rate, and query type. Multiple windows can run
 * simultaneously to simulate mixed load alongside transaction throughput tests.
 *
 * Supports sync and async modes. Sync mode blocks each client thread until the
 * result returns (measures round-trip latency). Async mode fires queries without
 * waiting, measuring latency via completion callbacks (much higher throughput).
 *
 * Metrics use exponential moving average with configurable decay per second.
 */
@SuppressWarnings("serial")
public class LoadSimulatorFrame extends JFrame {

	private static final AtomicInteger INSTANCE_COUNTER = new AtomicInteger(0);

	// ---- Configuration constants ----
	private static final double EMA_ALPHA = 0.5;           // EMA blend factor (50% decay per tick)
	private static final int METRICS_INTERVAL_MS = 1000;    // Metrics update period
	private static final int MAX_CLIENTS = 1000;
	private static final int DEFAULT_CLIENTS = 20;
	private static final int MAX_QPS_PER_CLIENT = 1_000;   // 1000 * 1,000 = 1M QPS target
	private static final int DEFAULT_QPS = 100;
	private static final long IDLE_SLEEP_MS = 200;          // Sleep when client is over limit
	private static final long EPOCH_RESET_NANOS = 1_000_000_000L; // Reset rate limiter every 1s
	private static final int MAX_IN_FLIGHT = 10;           // Max pending async queries per client

	// ---- Query definitions ----
	private static final String[] QUERY_NAMES = {
		"Constant",
		"Arithmetic",
		"Balance check",
		"Loop (x100 iter)",
		"Loop + Defs (x10)",
		"Build Vector (x50)"
	};
	private static final String[] QUERY_SOURCES = {
		"42",
		"(+ 1 2 3)",
		"(balance *address*)",
		"(loop [i 0] (if (< i 100) (recur (inc i)) i))",
		"(loop [i 0] (if (< i 10) (do (def x i) (recur (inc i))) x))",
		"(loop [v [] i 0] (if (< i 50) (recur (conj v i) (inc i)) (count v)))"
	};

	/** Pre-compiled CVM ops — compiled once per instance using the server's state */
	private final ACell[] compiledQueries;

	// ---- Controls ----
	private final JSlider clientSlider;
	private final JSlider rateSlider;
	private final JComboBox<String> queryTypeBox;
	private final JCheckBox asyncCheckbox;
	private final JToggleButton startStopButton;

	// ---- Metric displays ----
	private final JLabel qpsDisplay;
	private final JLabel latencyDisplay;
	private final JLabel successDisplay;
	private final JLabel targetDisplay;
	private final JLabel errorDisplay;

	// ---- Counters (reset every tick by metrics timer) ----
	private final AtomicLong completedCount = new AtomicLong();
	private final AtomicLong errorCount = new AtomicLong();
	private final AtomicLong totalLatencyNanos = new AtomicLong();

	// ---- EMA state (updated on EDT only) ----
	private double emaQps = 0;
	private double emaLatencyMs = 0;
	private double emaErrorPct = 0;
	private boolean emaInitialised = false;

	// ---- Cumulative error count for sub-label ----
	private long cumulativeErrors = 0;

	// ---- Runtime state ----
	private volatile boolean running = false;
	private final ArrayList<Thread> clientThreads = new ArrayList<>();
	private javax.swing.Timer metricsTimer;

	// ---- Server connection ----
	private final Server server;
	private final Address address;
	private final AKeyPair keyPair;

	public LoadSimulatorFrame(Server server, Address address, AKeyPair keyPair) {
		super("Load Simulator #" + INSTANCE_COUNTER.incrementAndGet());
		this.server = server;
		this.address = address;
		this.keyPair = keyPair;

		// Pre-compile queries to CVM ops using the server's current state
		Context ctx = Context.create(server.getPeer().getConsensusState(), address);
		compiledQueries = new ACell[QUERY_SOURCES.length];
		for (int i = 0; i < QUERY_SOURCES.length; i++) {
			ACell form = Reader.read(QUERY_SOURCES[i]);
			compiledQueries[i] = ctx.expandCompile(form).getResult();
		}

		// ---- Controls panel ----
		JPanel controls = new JPanel(new GridBagLayout());
		controls.setBorder(BorderFactory.createEmptyBorder(8, 12, 4, 12));
		GridBagConstraints c = new GridBagConstraints();
		c.insets = new Insets(3, 6, 3, 6);
		c.fill = GridBagConstraints.HORIZONTAL;

		// Clients
		c.gridx = 0; c.gridy = 0; c.weightx = 0;
		controls.add(label("Clients"), c);
		clientSlider = new JSlider(1, MAX_CLIENTS, DEFAULT_CLIENTS);
		clientSlider.setPaintTicks(true);
		clientSlider.setMajorTickSpacing(50);
		c.gridx = 1; c.weightx = 1.0;
		controls.add(clientSlider, c);
		JLabel clientValueLabel = new JLabel(String.valueOf(DEFAULT_CLIENTS), SwingConstants.RIGHT);
		clientValueLabel.setPreferredSize(new Dimension(50, 20));
		c.gridx = 2; c.weightx = 0;
		controls.add(clientValueLabel, c);
		clientSlider.addChangeListener(e -> {
			clientValueLabel.setText(String.valueOf(clientSlider.getValue()));
			updateTargetDisplay();
			if (running) adjustClients();
		});

		// Rate
		c.gridx = 0; c.gridy = 1; c.weightx = 0;
		controls.add(label("QPS/client"), c);
		rateSlider = new JSlider(1, MAX_QPS_PER_CLIENT, DEFAULT_QPS);
		rateSlider.setPaintTicks(true);
		rateSlider.setMajorTickSpacing(200);
		c.gridx = 1; c.weightx = 1.0;
		controls.add(rateSlider, c);
		JLabel rateValueLabel = new JLabel(String.valueOf(DEFAULT_QPS), SwingConstants.RIGHT);
		rateValueLabel.setPreferredSize(new Dimension(50, 20));
		c.gridx = 2; c.weightx = 0;
		controls.add(rateValueLabel, c);
		rateSlider.addChangeListener(e -> {
			rateValueLabel.setText(String.valueOf(rateSlider.getValue()));
			updateTargetDisplay();
		});

		// Query type + async toggle on same row
		c.gridx = 0; c.gridy = 2; c.weightx = 0;
		controls.add(label("Query"), c);
		queryTypeBox = new JComboBox<>(QUERY_NAMES);
		c.gridx = 1; c.weightx = 1.0;
		controls.add(queryTypeBox, c);
		asyncCheckbox = new JCheckBox("Async");
		asyncCheckbox.setToolTipText("Fire-and-forget queries (much higher throughput, latency measured via callbacks)");
		c.gridx = 2; c.weightx = 0;
		controls.add(asyncCheckbox, c);

		// ---- Metrics panel ----
		JPanel metrics = new JPanel(new GridBagLayout());
		metrics.setBackground(new Color(25, 28, 38));
		metrics.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createLineBorder(new Color(60, 65, 80), 1),
			BorderFactory.createEmptyBorder(16, 24, 16, 24)
		));

		GridBagConstraints m = new GridBagConstraints();
		m.insets = new Insets(2, 16, 2, 16);

		// QPS column
		m.gridx = 0; m.gridy = 0; m.anchor = GridBagConstraints.CENTER;
		metrics.add(metricLabel("QPS"), m);
		qpsDisplay = metricValue("\u2014", new Color(80, 230, 140));
		m.gridy = 1;
		metrics.add(qpsDisplay, m);

		// Latency column
		m.gridx = 1; m.gridy = 0;
		metrics.add(metricLabel("LATENCY"), m);
		latencyDisplay = metricValue("\u2014", new Color(90, 190, 255));
		m.gridy = 1;
		metrics.add(latencyDisplay, m);

		// Success column
		m.gridx = 2; m.gridy = 0;
		metrics.add(metricLabel("SUCCESS"), m);
		successDisplay = metricValue("\u2014", new Color(255, 200, 60));
		m.gridy = 1;
		metrics.add(successDisplay, m);

		// Sub-metrics row
		m.gridx = 0; m.gridy = 2; m.insets = new Insets(10, 16, 2, 16);
		targetDisplay = subMetricLabel("target: 2,000");
		metrics.add(targetDisplay, m);
		m.gridx = 1;
		errorDisplay = subMetricLabel("errors: 0");
		metrics.add(errorDisplay, m);

		// ---- Start/Stop button ----
		startStopButton = new JToggleButton("Stop");
		startStopButton.setSelected(true);
		startStopButton.setFont(Toolkit.BUTTON_FONT);
		startStopButton.setPreferredSize(new Dimension(140, 34));
		startStopButton.addActionListener(e -> {
			if (startStopButton.isSelected()) {
				startLoad();
				startStopButton.setText("Stop");
			} else {
				stopLoad();
				startStopButton.setText("Start");
			}
		});

		JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 0, 6));
		buttonPanel.add(startStopButton);

		// ---- Assembly ----
		setLayout(new BorderLayout(0, 4));
		getRootPane().setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));
		add(controls, BorderLayout.NORTH);
		add(metrics, BorderLayout.CENTER);
		add(buttonPanel, BorderLayout.SOUTH);

		updateTargetDisplay();
		pack();
		setMinimumSize(getSize());  // don't shrink smaller than packed size
		setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);

		// Auto-start load on launch
		startLoad();
	}

	// ---- Styling helpers ----

	private static JLabel label(String text) {
		JLabel l = new JLabel(text);
		return l;
	}

	private static JLabel metricLabel(String text) {
		JLabel l = new JLabel(text, SwingConstants.CENTER);
		l.setForeground(new Color(140, 145, 165));
		return l;
	}

	private static JLabel metricValue(String text, Color color) {
		JLabel l = new JLabel(text, SwingConstants.CENTER);
		l.setForeground(color);
		l.setFont(Toolkit.BIG_MONO_FONT);
		l.setPreferredSize(new Dimension(180, 48));
		return l;
	}

	private static JLabel subMetricLabel(String text) {
		JLabel l = new JLabel(text, SwingConstants.CENTER);
		l.setForeground(new Color(100, 105, 125));
		l.setFont(Toolkit.SMALL_MONO_FONT);
		return l;
	}

	private void updateTargetDisplay() {
		long target = (long) clientSlider.getValue() * rateSlider.getValue();
		targetDisplay.setText("target: " + Text.toFriendlyNumber(target));
	}

	// ---- Start / Stop ----

	private void startLoad() {
		running = true;
		emaQps = 0;
		emaLatencyMs = 0;
		emaErrorPct = 0;
		emaInitialised = false;
		cumulativeErrors = 0;
		completedCount.set(0);
		errorCount.set(0);
		totalLatencyNanos.set(0);
		qpsDisplay.setText("\u2014");
		latencyDisplay.setText("\u2014");
		successDisplay.setText("\u2014");
		errorDisplay.setText("errors: 0");

		adjustClients();

		metricsTimer = new javax.swing.Timer(METRICS_INTERVAL_MS, e -> updateMetrics());
		metricsTimer.start();
	}

	private void stopLoad() {
		running = false;
		if (metricsTimer != null) {
			metricsTimer.stop();
			metricsTimer = null;
		}
		for (Thread t : clientThreads) t.interrupt();
		clientThreads.clear();
	}

	/**
	 * Ensure enough client threads exist for the current slider value.
	 * Excess threads self-idle when their index >= slider value.
	 */
	private void adjustClients() {
		int target = clientSlider.getValue();
		while (clientThreads.size() < target) {
			int idx = clientThreads.size();
			Thread t = Thread.startVirtualThread(() -> clientLoop(idx));
			clientThreads.add(t);
		}
	}

	// ---- Client loop (virtual thread) ----

	private void clientLoop(int index) {
		try {
			ConvexLocal client = ConvexLocal.create(server, address, keyPair);
			try {
				// Epoch-based rate limiter: tracks queries sent vs time elapsed.
				// Self-correcting — if Thread.sleep overshoots (common on Windows
				// with ~15ms granularity), subsequent queries fire immediately
				// to catch up to the target rate.
				long epochNanos = System.nanoTime();
				long queryCount = 0;

				// Bounds in-flight async queries to prevent unbounded CF
				// accumulation and GC thrashing. Naturally throttles send
				// rate to match server processing capacity.
				Semaphore inFlight = new Semaphore(MAX_IN_FLIGHT);

				while (running) {
					// Idle if this client is over the limit
					if (index >= clientSlider.getValue()) {
						Thread.sleep(IDLE_SLEEP_MS);
						epochNanos = System.nanoTime();
						queryCount = 0;
						continue;
					}

					int rate = rateSlider.getValue();
					long elapsedNanos = System.nanoTime() - epochNanos;
					long allowed = (elapsedNanos * rate) / 1_000_000_000L;

					if (queryCount >= allowed) {
						// Ahead of schedule — sleep until next query is due
						long nextDueNanos = epochNanos + ((queryCount + 1) * 1_000_000_000L) / rate;
						long sleepMs = (nextDueNanos - System.nanoTime()) / 1_000_000;
						if (sleepMs > 0) Thread.sleep(sleepMs);
						continue;
					}

					ACell query = compiledQueries[queryTypeBox.getSelectedIndex()];
					queryCount++;

					if (asyncCheckbox.isSelected()) {
						// Async: fire query, measure latency in callback.
						// Semaphore limits in-flight queries to prevent
						// unbounded CF/memory growth.
						inFlight.acquire();
						long t0 = System.nanoTime();
						CompletableFuture<Result> cf = client.query(query, address);
						cf.whenComplete((r, ex) -> {
							long elapsed = System.nanoTime() - t0;
							if (ex != null || (r != null && r.isError())) {
								errorCount.incrementAndGet();
							} else {
								completedCount.incrementAndGet();
								totalLatencyNanos.addAndGet(elapsed);
							}
							inFlight.release();
						});
					} else {
						// Sync: block until result returns
						long t0 = System.nanoTime();
						Result r = client.querySync(query, address);
						long elapsed = System.nanoTime() - t0;
						if (r.isError()) {
							errorCount.incrementAndGet();
						} else {
							completedCount.incrementAndGet();
							totalLatencyNanos.addAndGet(elapsed);
						}
					}

					// Reset epoch every second to handle rate slider changes
					if (elapsedNanos > EPOCH_RESET_NANOS) {
						epochNanos = System.nanoTime();
						queryCount = 0;
					}
				}
			} finally {
				client.close();
			}
		} catch (InterruptedException e) {
			// normal shutdown
		} catch (Exception e) {
			// thread exits
		}
	}

	// ---- Metrics update (EDT via Timer) ----

	private void updateMetrics() {
		long completed = completedCount.getAndSet(0);
		long errors = errorCount.getAndSet(0);
		long latencyNanos = totalLatencyNanos.getAndSet(0);

		cumulativeErrors += errors;
		long total = completed + errors;

		double currentQps = completed;
		double currentLatencyMs = completed > 0
			? (latencyNanos / 1_000_000.0) / completed
			: 0;
		double currentErrorPct = total > 0
			? 100.0 * errors / total
			: 0;

		if (!emaInitialised && total > 0) {
			emaQps = currentQps;
			emaLatencyMs = currentLatencyMs;
			emaErrorPct = currentErrorPct;
			emaInitialised = true;
		} else if (emaInitialised) {
			emaQps = EMA_ALPHA * currentQps + (1 - EMA_ALPHA) * emaQps;
			emaLatencyMs = EMA_ALPHA * currentLatencyMs + (1 - EMA_ALPHA) * emaLatencyMs;
			emaErrorPct = EMA_ALPHA * currentErrorPct + (1 - EMA_ALPHA) * emaErrorPct;
		}

		if (emaInitialised) {
			qpsDisplay.setText(Text.toFriendlyIntString(emaQps));
			latencyDisplay.setText(String.format("%.2f ms", emaLatencyMs));
			double successPct = 100.0 - emaErrorPct;
			successDisplay.setText(String.format("%.1f%%", successPct));
		}

		errorDisplay.setText("errors: " + (cumulativeErrors > 0 ? Text.toFriendlyNumber(cumulativeErrors) : "0"));
	}

	@Override
	public void dispose() {
		stopLoad();
		super.dispose();
	}
}
