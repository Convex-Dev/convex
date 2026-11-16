package convex.net.ingress;

import convex.peer.Config;

/**
 * Limits of one {@link Ingress}.
 *
 * @param workers Workers decoding and routing messages
 * @param reservedWorkers Workers kept for the priority lane: the ordinary lane is
 *        served by at most {@code workers - reservedWorkers} at once
 * @param connectionMessages Messages one connection may have waiting before it stops
 *        being read; reading resumes at half
 * @param connectionBytes Encoded bytes one connection may have waiting before it
 *        stops being read; reading resumes at half
 * @param totalBytes Encoded bytes all connections may have waiting before none is
 *        read; reading resumes at half
 * @param priorityQuantum Encoded bytes a priority connection is served per turn
 * @param ordinaryQuantum Encoded bytes an ordinary connection is served per turn
 * @param minimumCost Encoded bytes each message counts as, at least
 * @param deadlineMillis Longest a message waits for its destination before it is
 *        refused
 */
public record IngressLimits(int workers, int reservedWorkers, int connectionMessages,
		long connectionBytes, long totalBytes, long priorityQuantum, long ordinaryQuantum,
		long minimumCost, long deadlineMillis) {

	/**
	 * Checks the limits are consistent.
	 *
	 * @throws IllegalArgumentException if a limit is out of range
	 */
	public IngressLimits {
		if (workers < 1) throw new IllegalArgumentException("Ingress needs a worker");
		if (reservedWorkers < 0 || reservedWorkers >= workers) {
			throw new IllegalArgumentException("Reserved workers must leave one for the ordinary lane");
		}
		if (connectionMessages < 2 || connectionBytes < 2 || totalBytes < 2) {
			throw new IllegalArgumentException("Ingress limits must allow two messages");
		}
		if (priorityQuantum < 1 || ordinaryQuantum < 1 || minimumCost < 1 || deadlineMillis < 0) {
			throw new IllegalArgumentException("Ingress quanta and costs must be positive");
		}
	}

	/**
	 * Gets the limits set in {@link Config}.
	 *
	 * @return Default limits
	 */
	public static IngressLimits defaults() {
		return new IngressLimits(Config.INGRESS_WORKERS, Config.INGRESS_RESERVED_WORKERS,
			Config.INGRESS_CONNECTION_MESSAGES, Config.INGRESS_CONNECTION_BYTES,
			Config.INGRESS_TOTAL_BYTES, Config.INGRESS_PRIORITY_QUANTUM,
			Config.INGRESS_ORDINARY_QUANTUM, Config.INGRESS_MINIMUM_COST,
			Config.DEFAULT_INTERNAL_TIMEOUT);
	}
}
