package convex.node;

import convex.core.data.ACell;
import convex.core.message.AConnection;

/**
 * Observes a complete inbound lattice value after the authoritative merge has
 * accepted it.
 *
 * <p>This is an application integration hook, not another validation layer.
 * {@link NodeServer} performs decoding, path resolution and lattice validation
 * before invoking the listener. Implementations may react to application-owned
 * paths, for example by updating a discovery index, but must not reinterpret the
 * merge result or mutate {@code path}.</p>
 *
 * <p>The callback runs on the node's single ordered inbound dispatcher. It must
 * return promptly and hand slow work to another executor. Listener failures are
 * contained and do not turn an already accepted lattice merge into a rejection.</p>
 */
@FunctionalInterface
public interface InboundLatticeListener {

	/**
	 * Handles one accepted inbound value.
	 *
	 * @param connection physical source connection, or null for local delivery
	 * @param propagator publication view assigned to the connection
	 * @param path canonical path at which the value was merged; do not mutate
	 * @param value complete value presented to the authoritative merge
	 * @param changed true when the merge changed the authoritative root
	 */
	void onAccepted(AConnection connection,LatticePropagator propagator,
		ACell[] path,ACell value,boolean changed);
}
