package convex.peer;

import java.util.ArrayList;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.core.Belief;
import convex.core.data.ACell;
import convex.core.data.Format;
import convex.core.data.Ref;
import convex.core.util.LatestUpdateQueue;
import convex.core.util.Utils;
import convex.net.message.Message;

/**
 * Component class to handle propagation of new Beliefs from a Peer
 * 
 * Overall logic:
 * 1. We want to propagate a new Belief delta as fast as possible once one is received
 * 2. We want to pause to ensure that as many peers as possible have received the delta
 * 
 */
public class BeliefPropagator extends AThreadedComponent {
	
	public static final int BELIEF_REBROADCAST_DELAY=300;
	
	/**
	 * Minimum delay between successive Belief broadcasts
	 */
	public static final int BELIEF_BROADCAST_DELAY=10;
	
	/**
	 * Polling period for Belief propagator loop
	 */
	public static final int BELIEF_BROADCAST_POLL_TIME=1000;
	
	/**
	 * Queue on which Beliefs are received from the Belief merge thread.
	 * 
	 * We use a custom TransferQueue because we only want to propagate the most recent Belief
	 */
	private BlockingQueue<Belief> beliefQueue=new LatestUpdateQueue<>();
	
	static final Logger log = LoggerFactory.getLogger(BeliefPropagator.class.getName());

	public BeliefPropagator(Server server) {
		super(server);
	}
	
	
	/**
	 * Check if the propagator wants the latest Belief for rebroadcast
	 * @return True is rebroadcast is due
	 */
	public boolean isRebroadcastDue() {
		return (lastBroadcastTime+BELIEF_REBROADCAST_DELAY)<Utils.getCurrentTimestamp();
	}

	
	/**
	 * Time of last belief broadcast
	 */
	long lastBroadcastTime=0;
	private long beliefBroadcastCount=0L;
	
	public long getBeliefBroadcastCount() {
		return beliefBroadcastCount;
	}
	
	/**
	 * Queues a Belief for broadcast
	 * @param belief Belief to queue
	 * @return True assuming Belief is queued successfully
	 */
	public synchronized boolean queueBelief(Belief belief) {
		return beliefQueue.offer(belief);
	}
	
	protected void loop() throws InterruptedException {
		Belief belief=beliefQueue.poll(BELIEF_BROADCAST_POLL_TIME, TimeUnit.MILLISECONDS);
		
		if (belief==null) {
			return;
		}

		ArrayList<ACell> novelty=new ArrayList<>();
		
		// At this point we know something updated our belief, so we want to rebroadcast
		// belief to network
		Consumer<Ref<ACell>> noveltyHandler = r -> {
			ACell o = r.getValue();
			novelty.add(o);
		};

		// persist the state of the Peer, announcing the new Belief
		// (ensure we can handle missing data requests etc.)
		belief=ACell.createAnnounced(belief, noveltyHandler);
		lastBroadcastBelief=belief;

		Message msg = Message.createBelief(belief, novelty);
		long mdc=msg.getMessageData().count();
		if (mdc>=Format.MAX_MESSAGE_LENGTH*0.95) {
			log.warn("Long Belief Delta message: "+mdc);
		}
		server.manager.broadcast(msg);
		
		lastBroadcastTime=Utils.getCurrentTimestamp();
		beliefBroadcastCount++;
		
		Thread.sleep(BELIEF_BROADCAST_DELAY);
	}

	private Belief lastBroadcastBelief;

	public Belief getLastBroadcastBelief() {
		return lastBroadcastBelief;
	}


	@Override
	protected String getThreadName() {
		return "Belief propagator thread on port "+server.getPort();
	}
}
