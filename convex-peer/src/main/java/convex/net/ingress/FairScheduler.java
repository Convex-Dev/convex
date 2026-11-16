package convex.net.ingress;

import java.util.ArrayDeque;

/**
 * Chooses the connection an ingress worker serves next. Connections wait in two
 * lanes, each served round-robin by surplus: a connection is served whenever its
 * credit is positive, and repays any overrun before it is served again. The lanes
 * take turns while both can be served, and the ordinary lane has a limited number
 * of workers at once.
 *
 * <p>Holds no threads and no lock: its {@link Ingress} calls it under its own
 * lock.</p>
 */
final class FairScheduler {

	private final ArrayDeque<IngressChannel> priority = new ArrayDeque<>();
	private final ArrayDeque<IngressChannel> ordinary = new ArrayDeque<>();
	private final int ordinaryWorkers;
	private final long priorityQuantum;
	private final long ordinaryQuantum;
	private int ordinaryServing = 0;
	private boolean lastPriority = false;

	/**
	 * Creates a scheduler.
	 *
	 * @param ordinaryWorkers Most turns the ordinary lane may have at once
	 * @param priorityQuantum Credit a priority connection gains per turn
	 * @param ordinaryQuantum Credit an ordinary connection gains per turn
	 */
	FairScheduler(int ordinaryWorkers, long priorityQuantum, long ordinaryQuantum) {
		this.ordinaryWorkers = ordinaryWorkers;
		this.priorityQuantum = priorityQuantum;
		this.ordinaryQuantum = ordinaryQuantum;
	}

	/**
	 * Queues a connection at the end of a lane.
	 *
	 * @param c Connection with messages to serve, not queued or being served
	 * @param inPriority Whether it goes in the priority lane
	 */
	void enqueue(IngressChannel c, boolean inPriority) {
		c.inPriority = inPriority;
		(inPriority ? priority : ordinary).addLast(c);
	}

	/**
	 * Removes a queued connection.
	 *
	 * @param c Connection queued by {@link #enqueue}
	 */
	void remove(IngressChannel c) {
		(c.inPriority ? priority : ordinary).remove(c);
	}

	/**
	 * Tests whether {@link #next()} would return a connection now.
	 *
	 * @return true if a lane can be served
	 */
	boolean hasEligible() {
		return !priority.isEmpty() || (!ordinary.isEmpty() && ordinaryServing < ordinaryWorkers);
	}

	/**
	 * Takes the connection to serve next, adding its lane's quantum to its credit.
	 * The caller serves it while its credit is positive, then calls
	 * {@link #finish(IngressChannel)}.
	 *
	 * @return Connection to serve, with positive credit, or null if no lane can be served
	 */
	IngressChannel next() {
		boolean ordinaryOpen = !ordinary.isEmpty() && ordinaryServing < ordinaryWorkers;
		boolean priorityOpen = !priority.isEmpty();
		if (!ordinaryOpen && !priorityOpen) return null;
		boolean takePriority = priorityOpen && (!ordinaryOpen || !lastPriority);
		IngressChannel c = takePriority ? take(priority, priorityQuantum) : take(ordinary, ordinaryQuantum);
		lastPriority = takePriority;
		if (!takePriority) ordinaryServing++;
		return c;
	}

	/**
	 * Ends the turn of a connection {@link #next()} returned, before it is queued
	 * again.
	 *
	 * @param c Connection whose turn ends
	 */
	void finish(IngressChannel c) {
		if (!c.inPriority) ordinaryServing--;
	}

	/** Number of turns the ordinary lane has now. For tests. */
	int ordinaryServing() {
		return ordinaryServing;
	}

	/**
	 * Takes the first connection in a non-empty lane whose credit is positive after
	 * its quantum. Each connection passed over has repaid one quantum.
	 */
	private static IngressChannel take(ArrayDeque<IngressChannel> lane, long quantum) {
		int n = lane.size();
		for (int i = 0; i < n; i++) {
			IngressChannel c = lane.pollFirst();
			c.credit += quantum;
			if (c.credit > 0) return c;
			lane.addLast(c);
		}
		// A whole round of repayment served no one: repay the rounds until the first is clear at once
		long rounds = Long.MAX_VALUE;
		for (IngressChannel c : lane) rounds = Math.min(rounds, (quantum - c.credit) / quantum);
		for (IngressChannel c : lane) c.credit += rounds * quantum;
		while (true) {
			IngressChannel c = lane.pollFirst();
			if (c.credit > 0) return c;
			lane.addLast(c);
		}
	}
}
