package convex.peer;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
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
import convex.core.util.ConsumerDispatcher;
import convex.core.util.LatestUpdateQueue;
import convex.core.util.LoadMonitor;
import convex.core.util.Utils;

/**
 * Component handling CVM execution loop with a Peer Server
 */
public class CVMExecutor extends AThreadedComponent {
	
	private static final Logger log = LoggerFactory.getLogger(CVMExecutor.class.getName());
	
	/**
	 * Latest immutable Peer snapshot. The executor publishes replacements while
	 * network, query and transaction threads read them concurrently.
	 */
	private volatile Peer peer;
	
	/**
	 * Dispatcher for observing finalised peer state updates. Kept null for the
	 * no-observer fast path.
	 */
	private volatile ConsumerDispatcher<Peer> updateObservers;

	/** Compatibility hook installed through setUpdateHook. */
	private Consumer<Peer> updateHook;
	
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
			boolean stateAdvanced=false;
			synchronized(this) {
				if (beliefUpdate!=null) {
					peer=peer.updateBelief(beliefUpdate);
				}

				// Trigger State update (if any new Blocks are confirmed)
				Peer updatedPeer=peer.updateState();
				if (updatedPeer!=peer) {
					peer=updatedPeer;
					persistPeerData();
					maybeCallObservers(peer);
					stateAdvanced=true;
				}
			}
			if (stateAdvanced) completeStatePositionWaiters();

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

	private void maybeCallObservers(Peer p) {
		ConsumerDispatcher<Peer> observers=updateObservers;
		if (observers!=null) observers.accept(p);
	}

	@Override
	protected String getThreadName() {
		return "CVM Executor thread on port "+server.getPort();
	}

	public void setPeer(Peer peer) {
		synchronized (this) {
			this.peer=peer;
		}
		completeStatePositionWaiters();
	}

	public Peer getPeer() {
		return peer;
	}

	/**
	 * Waiters for the peer's state position to reach a target, newest last. Kept small:
	 * entries are removed as soon as they are satisfied.
	 */
	private final java.util.List<StatePositionWaiter> statePositionWaiters=new java.util.ArrayList<>();

	private static final class StatePositionWaiter {
		final long position;
		final CompletableFuture<Peer> future=new CompletableFuture<>();

		StatePositionWaiter(long position) {
			this.position=position;
		}
	}

	/**
	 * Gets a future completing with the Peer once its state has been computed to at least
	 * the given block position.
	 *
	 * <p>A real signal to wait on instead of polling {@link #getPeer()} or sleeping. State
	 * position advances asynchronously on the executor thread, so a caller that reads the
	 * peer immediately after launch or after submitting work may observe an earlier
	 * position. Note the position can also move <em>backwards</em>: a peer whose finality
	 * point is behind its state truncates on update, so this completes on the first
	 * observation at or beyond the target rather than promising the peer stays there.</p>
	 *
	 * @param position Block position the state must reach
	 * @return Future completing with the Peer at or beyond that state position
	 */
	public CompletableFuture<Peer> awaitStatePosition(long position) {
		StatePositionWaiter waiter;
		synchronized (this) {
			Peer p=peer;
			if ((p!=null)&&(p.getStatePosition()>=position)) return CompletableFuture.completedFuture(p);
			waiter=new StatePositionWaiter(position);
			statePositionWaiters.add(waiter);
		}
		// Re-check outside the fast path: the executor may have advanced between the read
		// above and the registration, which would otherwise leave this waiter unnotified.
		completeStatePositionWaiters();
		return waiter.future;
	}

	/**
	 * Completes any waiters the current state position has reached. Futures are completed
	 * outside the lock so dependent actions cannot run while holding the executor monitor.
	 */
	private void completeStatePositionWaiters() {
		java.util.List<StatePositionWaiter> ready=null;
		Peer p;
		synchronized (this) {
			p=peer;
			if ((p==null)||statePositionWaiters.isEmpty()) return;
			long pos=p.getStatePosition();
			java.util.Iterator<StatePositionWaiter> it=statePositionWaiters.iterator();
			while (it.hasNext()) {
				StatePositionWaiter w=it.next();
				if (pos>=w.position) {
					if (ready==null) ready=new java.util.ArrayList<>();
					ready.add(w);
					it.remove();
				}
			}
		}
		if (ready!=null) for (StatePositionWaiter w: ready) w.future.complete(p);
	}

	public void queueUpdate(Belief belief) {
		update.offer(belief);
	}

	/**
	 * Adds an observer for finalised peer state updates.
	 */
	synchronized boolean addUpdateObserver(Consumer<Peer> observer) {
		ConsumerDispatcher<Peer> observers=updateObservers;
		boolean added;
		if (observers==null) {
			observers=new ConsumerDispatcher<>();
			added=observers.add(observer);
			updateObservers=observers;
		} else {
			added=observers.add(observer);
		}
		if (added&&(peer!=null)) {
			try {
				observer.accept(peer);
			} catch (Exception e) {
				log.debug("State update observer failed during registration",e);
			}
		}
		return added;
	}

	/**
	 * Removes a finalised peer state observer.
	 */
	synchronized boolean removeUpdateObserver(Consumer<Peer> observer) {
		ConsumerDispatcher<Peer> observers=updateObservers;
		if (observers==null) return false;
		boolean removed=observers.remove(observer);
		if (removed&&observers.isEmpty()) updateObservers=null;
		return removed;
	}

	/**
	 * Replaces the legacy singleton update hook. New code should use
	 * {@link Server#addStateUpdateObserver(Consumer)} and
	 * {@link Server#removeStateUpdateObserver(Consumer)}.
	 *
	 * @param hook New hook, or {@code null} to clear it
	 * @deprecated Use the additive Server observation point
	 */
	@Deprecated
	public synchronized void setUpdateHook(Consumer<Peer> hook) {
		Consumer<Peer> oldHook=updateHook;
		if (oldHook!=null) removeUpdateObserver(oldHook);
		updateHook=hook;
		if (hook!=null) addUpdateObserver(hook);
	}





}
