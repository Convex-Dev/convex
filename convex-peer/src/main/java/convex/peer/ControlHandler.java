package convex.peer;

import java.util.ArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import convex.core.message.Message;
import convex.core.util.LoadMonitor;
import convex.net.ingress.QueueDestination;

/**
 * Handles the control messages that arrive on network connections: PING, STATUS,
 * CHALLENGE and GOODBYE. They queue here, so that ingress workers only decode and
 * route, and a connection that sends them faster than they are answered is paused
 * like any other.
 */
class ControlHandler extends AThreadedComponent {

	private final QueueDestination queue = new QueueDestination(Config.CONTROL_QUEUE_SIZE, Config.DEFAULT_INTERNAL_TIMEOUT);

	/** Reusable drain buffer, only accessed from the handler thread */
	private final ArrayList<Message> batch = new ArrayList<>();

	ControlHandler(Server server) {
		super(server);
		queue.setConsumer(thread);
	}

	/**
	 * Offers a control message for handling.
	 *
	 * @param m Decoded control message
	 * @return null if queued, otherwise the queue, to wait for room
	 */
	Predicate<Message> offer(Message m) {
		return queue.tryOffer(m) ? null : queue;
	}

	@Override
	protected void loop() throws InterruptedException {
		LoadMonitor.down();
		Message first = queue.poll(10000, TimeUnit.MILLISECONDS);
		LoadMonitor.up();
		if (first == null) return;
		try {
			batch.add(first);
			queue.drainTo(batch);
			// Each is contained to its own message, unless the JVM itself has failed
			for (int i = 0; i < batch.size(); i++) server.processMessage(batch.get(i));
		} finally {
			// Released even if this pass fails: recovering from running out of memory relies on it
			batch.clear();
		}
	}

	@Override
	protected String getThreadName() {
		return "Control handler on port: " + server.getPort();
	}
}
