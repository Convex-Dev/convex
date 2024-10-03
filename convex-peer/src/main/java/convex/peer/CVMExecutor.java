package convex.peer;

import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.core.cpos.Belief;
import convex.core.cvm.Peer;
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
		
		try {
			synchronized(this) {
				if (beliefUpdate!=null) {
					peer=peer.updateBelief(beliefUpdate);
				}
				
				// Trigger State update (if any new Blocks are confirmed)
				Peer updatedPeer=peer.updateState();
				if (updatedPeer!=peer) {
					peer=updatedPeer;
					try {
						persistPeerData();
					} catch (IOException e) {
						log.debug("IO Exception ("+e.getMessage()+") while persisting peer data",e);
						throw new InterruptedException("IO Exception while persisting peer data");
					}
					maybeCallHook(peer);
				}
			}
			
			server.transactionHandler.maybeReportTransactions(peer);
		} catch (Exception e) {
			// This is some fatal failure
			log.error("Fatal exception encountered in CVM Executor",e);
			server.close();
		}
	}
	
	public void syncPeer(Server base) {
		// TODO Auto-generated method stub
		
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
