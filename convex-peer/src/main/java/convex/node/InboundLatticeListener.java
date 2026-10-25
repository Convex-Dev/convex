package convex.node;

import convex.core.data.ACell;
import convex.core.message.AConnection;

/**
 * Observes a complete inbound lattice value after the host has accepted it.
 *
 * <p>This is an application integration hook, not another validation layer. The
 * owning propagation endpoint performs decoding and admission; {@link NodeServer}
 * hands the value to its host, whose applications merge it, before the listener
 * is invoked. Implementations may react to application-owned paths,
 * for example by updating a discovery index, but must not reinterpret the merge
 * result or mutate {@code path}.</p>
 *
 * <p>The callback runs on the propagation group's ordered inbound dispatcher. It
 * must return promptly and hand slow work to another executor. Listener failures
 * are contained and do not turn an accepted merge into a rejection.</p>
 */
@FunctionalInterface
public interface InboundLatticeListener {

	/**
	 * Handles one accepted inbound value.
	 *
	 * @param connection physical source connection, or null for local delivery
	 * @param propagator propagation group assigned to the connection
	 * @param path canonical path at which the value was merged; do not mutate
	 * @param value complete value presented to the host
	 * @param changed true when the merge changed the host root
	 */
	void onAccepted(AConnection connection,LatticePropagator propagator,
		ACell[] path,ACell value,boolean changed);
}
