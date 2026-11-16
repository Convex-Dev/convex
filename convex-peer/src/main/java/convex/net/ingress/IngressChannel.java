package convex.net.ingress;

import java.util.ArrayDeque;
import java.util.function.Function;
import java.util.function.Predicate;

import convex.core.message.AConnection;
import convex.core.message.Message;

/**
 * One connection's place in an {@link Ingress}: the messages it has framed, waiting
 * in order to be decoded and routed. Its transport hands each framed message to
 * {@link #admit(Message)} and stops reading when told to, until the ingress calls
 * the resume action it registered.
 */
public final class IngressChannel {

	/** Where a connection is in its ingress. */
	enum State {
		/** No messages waiting */
		IDLE,
		/** In a scheduler lane */
		QUEUED,
		/** Being served by a worker */
		RUNNING,
		/** Its first message waits for a destination or a retry */
		BLOCKED
	}

	/** A waiting message and its encoded length. */
	record Entry(Message message, int bytes) {}

	final Ingress ingress;
	final AConnection connection;
	final Function<Message, Predicate<Message>> route;
	final Runnable resumeReading;

	// Guarded by the ingress lock
	final ArrayDeque<Entry> waiting = new ArrayDeque<>();
	long bytes;
	long credit;
	boolean inPriority;
	State state = State.IDLE;
	boolean paused;
	boolean closed;
	/** What the first message waits for: a Destination, or a retry predicate running on its own thread */
	Predicate<Message> blockedOn;
	/** Counts the first messages that blocked, so actions for an earlier one are ignored */
	long episode;
	/** Set while running when the first message may now be placed */
	boolean signalled;
	/** Set when the first message has waited for its destination until the deadline */
	boolean expired;
	/** Set while running when a retry has finished with the first message */
	boolean headDone;
	Runnable cancelDeadline;

	IngressChannel(Ingress ingress, AConnection connection, Function<Message, Predicate<Message>> route,
			Runnable resumeReading) {
		this.ingress = ingress;
		this.connection = connection;
		this.route = route;
		this.resumeReading = resumeReading;
	}

	/**
	 * Hands over a framed message, which this channel always takes. Messages are
	 * routed in the order they are admitted.
	 *
	 * @param message Message framed from this connection
	 * @return true if the transport may keep reading; false if it must stop until
	 *         the resume action runs
	 */
	public boolean admit(Message message) {
		return ingress.admit(this, message);
	}

	/**
	 * Releases this channel when its connection closes: waiting messages are
	 * discarded and nothing more is admitted. Idempotent.
	 */
	public void disconnect() {
		ingress.disconnect(this);
	}

	/**
	 * Tests whether the transport should still not read this connection: the last
	 * {@link #admit(Message)} returned false, and the resume action has not been
	 * called since.
	 *
	 * @return true while reading is paused
	 */
	public boolean isPaused() {
		return ingress.isPaused(this);
	}

	/**
	 * Gets the connection this channel serves.
	 *
	 * @return Connection
	 */
	public AConnection getConnection() {
		return connection;
	}
}
