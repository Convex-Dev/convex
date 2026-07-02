package convex.peer;

import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.core.cpos.Belief;
import convex.core.cvm.Peer;
import convex.core.exceptions.TODOException;
import convex.core.exceptions.UpgradeError;
import convex.core.util.LatestUpdateQueue;
import convex.core.util.LoadMonitor;

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
				log.warn("Upgrade to protocol version {} could not be applied due to a local condition; will retry",
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
