package convex.peer;

import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.core.Belief;
import convex.core.Peer;
import convex.core.data.ACell;
import convex.core.data.Ref;
import convex.core.data.SignedData;
import convex.core.store.Stores;
import convex.core.util.Utils;
import convex.net.message.Message;

/**
 * Component class to handle propagation of new Beliefs from a Peer
 */
public class BeliefPropagator {
	
	public static final int MIN_BELIEF_BROADCAST_DELAY=30;

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
					synchronized(BeliefPropagator.this) {BeliefPropagator.this.wait(1000);};
					
					long delay=(lastBroadcastTime+MIN_BELIEF_BROADCAST_DELAY)-Utils.getCurrentTimestamp();
					if (delay>0) Thread.sleep(Math.min(1000, delay));
					Peer peer=latestPeer;
					latestPeer=null;
					doBroadcastBelief(peer);
				}catch (InterruptedException e) {
					log.trace("Belief Propagator thread interrupted on "+server);
				}
			}
		}
	};
	
	protected final Thread beliefPropagatorThread=new Thread(beliefPropagatorLoop);
	
	/**
	 * Time of last belief broadcast
	 */
	long lastBroadcastTime=0;
	private long beliefBroadcastCount=0L;

	private Peer latestPeer;
	private SignedData<Belief> lastSignedBelief;
	
	public long getBeliefBroadcastCount() {
		return beliefBroadcastCount;
	}
	
	public synchronized void broadcastBelief(Peer peer) {
		this.latestPeer=peer;
		notify();
	}
	
	private void doBroadcastBelief(Peer peer) {
		if (peer==null) return;

		// Broadcast latest Belief to connected Peers
		SignedData<Belief> sb = peer.getSignedBelief();
		if (Utils.equals(sb, lastSignedBelief)) return; // don't broadcast again
		Belief belief=sb.getValue();
		
		// At this point we know something updated our belief, so we want to rebroadcast
		// belief to network
		Consumer<Ref<ACell>> noveltyHandler = r -> {
			ACell o = r.getValue();
			if (o == belief) return; // skip sending data for belief cell itself, will be BELIEF payload
			Message msg = Message.createData(o);
            // broadcast to all peers trusted or not
			server.manager.broadcast(msg, false);
		};

		// persist the state of the Peer, announcing the new Belief
		// (ensure we can handle missing data requests etc.)
		peer=peer.persistState(noveltyHandler);
		server.reportPeerBroadcast(peer); 

		Message msg = Message.createBelief(sb);

		server.manager.broadcast(msg, false);
		lastBroadcastTime=Utils.getCurrentTimestamp();
		lastSignedBelief=sb;
		beliefBroadcastCount++;
	}

	public void close() {
		beliefPropagatorThread.interrupt();
	}

	public void start() {
		// TODO Auto-generated method stub
		beliefPropagatorThread.start();
	}
}
