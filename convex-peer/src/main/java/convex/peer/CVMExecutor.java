package convex.peer;

import java.io.IOException;
import java.util.ArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.core.Belief;
import convex.core.Block;
import convex.core.Peer;
import convex.core.data.SignedData;
import convex.core.transactions.ATransaction;
import convex.core.util.LatestUpdateQueue;
import convex.core.util.LoadMonitor;
import convex.core.util.Utils;

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
		
		synchronized(this) {
			if (beliefUpdate!=null) {
				peer=peer.updateBelief(beliefUpdate);
			}
			
			// Parallel signature validation
			prevalidateSignatures(peer);
			
			// Trigger State update (if any new Blocks are confirmed)
			Peer updatedPeer=peer.updateState();
			if (updatedPeer!=peer) {
				peer=updatedPeer;
				persistPeerData();
				maybeCallHook(peer);
			}
		}
		
		server.transactionHandler.maybeReportTransactions(peer);
	}
	
	private final ArrayList<SignedData<ATransaction>> txs=new ArrayList<>();
	
	private void prevalidateSignatures(Peer peer) {
		long consensusPoint=peer.getFinalityPoint();
		long statePos=peer.getStatePosition();
		
		txs.clear();
		for (long i=statePos; i<consensusPoint; i++) {
			Block b=peer.getPeerOrder().getBlock(i).getValue();
			txs.addAll(b.getTransactions());
		}
		
		txs.stream().parallel().forEach(validateTransactionSignature);
	}

	private final Consumer<SignedData<ATransaction>> validateTransactionSignature = st-> {
		st.checkSignature();
	};
	
	public synchronized void persistPeerData() {
		try {
			peer = server.persistPeerData();
		} catch (IOException e) {
			log.warn("Exception while attempting to persist Peer data",e);
			throw Utils.sneakyThrow(e);
		}
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
