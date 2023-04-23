package convex.peer;

import java.util.concurrent.TimeUnit;

import convex.core.Belief;
import convex.core.Peer;
import convex.core.util.LatestUpdateQueue;
import convex.core.util.LoadMonitor;

/**
 * Component handling CVM execution loop with a Peer Server
 */
public class CVMExecutor extends AThreadedComponent {
	
	private Peer peer;
	
	private LatestUpdateQueue<Belief> update=new LatestUpdateQueue<>();

	public CVMExecutor(Server server) {
		super(server);
	}

	@Override
	protected void loop() throws InterruptedException {
		// poll for any Belief change
		LoadMonitor.down();
		Belief beliefUpdate=update.poll(1000, TimeUnit.MILLISECONDS);
		LoadMonitor.up();
		if (beliefUpdate!=null) {
			peer=peer.updateBelief(beliefUpdate);
		}
		
		// Trigger State update (if any new Blocks are confirmed)
		peer=peer.updateState();
		
		server.transactionHandler.maybeReportTransactions(peer);
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

}
