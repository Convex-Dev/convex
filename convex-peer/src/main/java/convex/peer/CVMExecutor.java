package convex.peer;

import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;

import convex.core.cpos.Belief;
import convex.core.cvm.Migrations;
import convex.core.cvm.Peer;
import convex.core.exceptions.TODOException;
import convex.core.exceptions.UpgradeError;
import convex.core.util.LatestUpdateQueue;
import convex.core.util.LoadMonitor;
import convex.core.util.Utils;

/**
 * Component handling CVM execution loop with a Peer Server
 */
public class CVMExecutor extends AThreadedComponent {
	
	private static final Logger log = LoggerFactory.getLogger(CVMExecutor.class.getName());
	
	private Peer peer;
	
	/**
	 * Hook for observing peer updates
	 */
	private Consumer<Peer> updateHook=null;
	
	/**
	 * Queue for latest incoming Beliefs
	 */
	private LatestUpdateQueue<Belief> update=new LatestUpdateQueue<>();

	/** Re-warn interval for a pending unsupported upgrade (24h). */
	private static final long UPGRADE_WARN_INTERVAL = 24L * 60 * 60 * 1000;
	/** Activation last warned about (-1 = none), so a newly scheduled upgrade warns at once. */
	private long lastWarnedActivation = -1;
	/** Wall-clock time (ms) of the last unsupported-upgrade warning. */
	private long lastWarnTime = 0;

	public CVMExecutor(Server server) {
		super(server);
	}

	@Override
	protected void loop() throws InterruptedException {
		// poll for any Belief change
		LoadMonitor.down();
		Belief beliefUpdate=update.poll(100, TimeUnit.MILLISECONDS);
		LoadMonitor.up();

		// If consensus is frozen pending a software upgrade, do no consensus work.
		// The peer stays alive to serve queries; the poll above still paces the loop.
		if (server.isConsensusHalted()) return;

		// Early warning: while still operating, alert the operator if the schedule
		// already contains an upgrade this release cannot apply (see below).
		maybeWarnUpgrade();

		try {
			synchronized(this) {
				if (beliefUpdate!=null) {
					peer=peer.updateBelief(beliefUpdate);
				}

				// Trigger State update (if any new Blocks are confirmed)
				Peer updatedPeer=peer.updateState();
				if (updatedPeer!=peer) {
					peer=updatedPeer;
					persistPeerData();
					maybeCallHook(peer);
				}
			}

			server.transactionHandler.maybeReportTransactions(peer);
		} catch (UpgradeError e) {
			// A required network upgrade cannot be applied by this release (see UPGRADE.md).
			// UpgradeError is an Error, so it bypasses applyBlock's catch(Exception) and is
			// never turned into an invalid block - state at the boundary is left uncommitted.
			if (e.isRetryable()) {
				// Peer-local (e.g. missing store data): resync-and-retry may succeed with no
				// release change, so do NOT freeze. The loop retries on subsequent iterations.
				Server.upgradeLog.warn("Upgrade to protocol version {} could not be applied due to a local condition; will retry",
						e.getVersion(), e);
			} else {
				// Deterministic: this release cannot proceed. Freeze consensus (executor and
				// propagator both cease) until the operator updates and restarts.
				server.haltConsensus(e);
			}
		} catch (Exception e) {
			// This is some fatal failure
			log.error("Fatal exception encountered in CVM Executor",e);
			server.close();
		}
	}

	/**
	 * Warns the operator when the consensus schedule already contains a network
	 * upgrade this release cannot apply, giving lead time to update before the peer
	 * withdraws at the activation (see UPGRADE.md). Warns immediately the first time
	 * a given upgrade is seen, then at most once per {@link #UPGRADE_WARN_INTERVAL}
	 * so it stays visible in recent logs without spamming.
	 *
	 * <p>Lives here, in the executor, rather than in the connection manager: the
	 * executor runs for every peer (including local/in-process peers, where the
	 * connection manager does no work), and it already owns the peer-level upgrade
	 * lifecycle — it is this loop that freezes at the boundary. Detection itself is
	 * a cheap pure function ({@link Server#getUpgradeWarning()}).</p>
	 */
	private void maybeWarnUpgrade() {
		Migrations.UpgradeWarning w = server.getUpgradeWarning();
		if (w == null) {
			lastWarnedActivation = -1; // reset so a future scheduling warns immediately
			return;
		}
		long now = Utils.getCurrentTimestamp();
		if ((w.activation != lastWarnedActivation) || (now - lastWarnTime >= UPGRADE_WARN_INTERVAL)) {
			Server.upgradeLog.warn("UPGRADE REQUIRED: protocol version {} is scheduled to activate at {} ({}), but this peer release supports protocol version {}. Update the peer software before then, or this peer will withdraw from consensus at that time. See UPGRADE.md",
					w.version, w.activation, Instant.ofEpochMilli(w.activation), Migrations.MAX_VERSION);
			lastWarnedActivation = w.activation;
			lastWarnTime = now;
		}
	}

	public void syncPeer(Server base) {
		// TODO Auto-generated method stub
		throw new TODOException();
	}
	
	public synchronized void recalcState(long pos) {
		// TODO Auto-generated method stub
		peer=peer.recalcState(pos);
	}
	
	public synchronized void persistPeerData() throws IOException {
		peer = server.persistPeerData();

	}

	private void maybeCallHook(Peer p) {
		Consumer<Peer> hook=updateHook;
		if (hook==null) return;
		
		hook.accept(p);
	}

	@Override
	protected String getThreadName() {
		return "CVM Executor thread on port "+server.getPort();
	}

	public synchronized void setPeer(Peer peer) {
		this.peer=peer;
	}
	
	public Peer getPeer() {
		return peer;
	}

	public void queueUpdate(Belief belief) {
		update.offer(belief);
	}

	public void setUpdateHook(Consumer<Peer> hook) {
		updateHook=hook;
	}





}
