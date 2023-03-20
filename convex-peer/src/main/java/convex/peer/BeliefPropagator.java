package convex.peer;

import java.util.function.Consumer;

import convex.core.Belief;
import convex.core.Peer;
import convex.core.data.ACell;
import convex.core.data.Ref;
import convex.core.data.SignedData;
import convex.core.util.Utils;
import convex.net.message.Message;

/**
 * Component class to handle propagation of new Beliefs from a Peer
 */
public class BeliefPropagator {

	protected final Server server;

	public BeliefPropagator(Server server) {
		this.server=server;
	}
	/**
	 * Time of last belief broadcast
	 */
	long lastBroadcastBelief=0;
	private long beliefBroadcastCount=0L;
	
	public long getBeliefBroadcastCount() {
		return beliefBroadcastCount;
	}
	
	void broadcastBelief(Peer peer) {
		Belief belief=peer.getBelief();
		
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

		// Broadcast latest Belief to connected Peers
		SignedData<Belief> sb = peer.getSignedBelief();

		Message msg = Message.createBelief(sb);

		server.manager.broadcast(msg, false);
		lastBroadcastBelief=Utils.getCurrentTimestamp();
		beliefBroadcastCount++;
	}

}
