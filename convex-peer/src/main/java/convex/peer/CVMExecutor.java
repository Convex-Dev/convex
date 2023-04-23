package convex.peer;

import java.util.concurrent.TimeUnit;

import convex.core.Belief;
import convex.core.Peer;
import convex.core.util.LatestUpdateQueue;

public class CVMExecutor extends AThreadedComponent {
	
	private Peer peer;
	
	private LatestUpdateQueue<Belief> update=new LatestUpdateQueue<>();

	public CVMExecutor(Server server) {
		super(server);
	}

	@Override
	protected void loop() throws InterruptedException {
		Belief beliefUpdate=update.poll(1000, TimeUnit.MILLISECONDS);
		if (beliefUpdate!=null) {
			peer=peer.updateBelief(beliefUpdate);
		}
		
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
