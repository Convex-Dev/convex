package convex.peer;

import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.core.Belief;
import convex.core.Peer;
import convex.core.util.LatestUpdateQueue;
import convex.core.util.LoadMonitor;

/**
 * Component handling CVM execution loop with a Peer Server
 */
public class CVMExecutor extends AThreadedComponent {
	
	private static final Logger log = LoggerFactory.getLogger(CVMExecutor.class.getName());

	
	private Peer peer;
	
	private Consumer<Peer> updateHook=null;
	
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
		if (beliefUpdate!=null) {
			peer=peer.updateBelief(beliefUpdate);
		}
		
		// Trigger State update (if any new Blocks are confirmed)
		Peer updatedPeer=peer.updateState();
		if (updatedPeer!=peer) {
			peer=updatedPeer;
			try {
				peer = server.persistPeerData();
			} catch (Exception e) {
				log.warn("Unable to persist Peer data: ",e);
			}
			maybeCallHook(peer);
		}
		
		server.transactionHandler.maybeReportTransactions(peer);
	}

	private void maybeCallHook(Peer p) {
		Consumer<Peer> hook=updateHook;
		if (hook==null) return;
		
		try {
			hook.accept(p);
		} catch (Throwable t) {
			// Ignore
		}
	}

	@Override
	protected String getThreadName() {
		return "CVM Executor thread on port "+server.getPort();
	}

	public void setPeer(Peer peer) {
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
