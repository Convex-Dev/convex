package convex.peer;

import java.util.ArrayList;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.core.Belief;
import convex.core.Peer;
import convex.core.data.ACell;
import convex.core.data.Ref;
import convex.core.store.Stores;
import convex.core.util.Utils;
import convex.net.message.Message;

/**
 * Component class to handle propagation of new Beliefs from a Peer
 */
public class BeliefPropagator {
	
	public static final int MIN_BELIEF_BROADCAST_DELAY=50;
	public static final int BELIEF_REBROADCAST_DELAY=2000;

	protected final Server server;
	
	static final Logger log = LoggerFactory.getLogger(BeliefPropagator.class.getName());

	public BeliefPropagator(Server server) {
		this.server=server;
	}
	
	protected final Runnable beliefPropagatorLoop = new Runnable() {
		@Override
		public void run() {
			Stores.setCurrent(server.getStore());
			while (server.isLive()) {
				try {
					// wait until the thread is notified of new work
					synchronized(BeliefPropagator.this) {BeliefPropagator.this.wait(BELIEF_REBROADCAST_DELAY);};
					
					if (!isBroadcastDue()) Thread.sleep(MIN_BELIEF_BROADCAST_DELAY);
					Peer peer=latestPeer;
					latestPeer=null;
					doBroadcastBelief(peer);
				
				} catch (InterruptedException e) {
					log.trace("Belief Propagator thread interrupted on "+server);
				} catch (Throwable e) {
					log.warn("Unexpected exception in Belief propagator: ",e);
				}
			}
		}
	};
	
	public boolean isBroadcastDue() {
		return (lastBroadcastTime+MIN_BELIEF_BROADCAST_DELAY)<Utils.getCurrentTimestamp();
	}
	
	protected final Thread beliefPropagatorThread=new Thread(beliefPropagatorLoop);
	
	/**
	 * Time of last belief broadcast
	 */
	long lastBroadcastTime=0;
	private long beliefBroadcastCount=0L;

	private Peer latestPeer;
	
	public long getBeliefBroadcastCount() {
		return beliefBroadcastCount;
	}
	
	public synchronized void broadcastBelief(Peer peer) {
		this.latestPeer=peer;
		notify();
	}
	
	private void doBroadcastBelief(Peer peer) {
		if (peer==null) return;

		ArrayList<ACell> novelty=new ArrayList<>();
		
		// At this point we know something updated our belief, so we want to rebroadcast
		// belief to network
		Consumer<Ref<ACell>> noveltyHandler = r -> {
			ACell o = r.getValue();
			novelty.add(o);
		};

		// persist the state of the Peer, announcing the new Belief
		// (ensure we can handle missing data requests etc.)
		peer=peer.persistState(noveltyHandler);
		server.reportPeerBroadcast(peer); 

		Message msg = Message.createBelief(peer.getBelief(), novelty);
		server.manager.broadcast(msg, false);
		
		lastBroadcastTime=Utils.getCurrentTimestamp();
		beliefBroadcastCount++;
	}

	public void close() {
		beliefPropagatorThread.interrupt();
	}

	public void start() {
		beliefPropagatorThread.start();
	}
}
