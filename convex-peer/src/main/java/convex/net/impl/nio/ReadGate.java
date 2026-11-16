package convex.net.impl.nio;

import java.nio.channels.CancelledKeyException;
import java.nio.channels.SelectableChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.util.function.Consumer;

import convex.core.message.Message;
import convex.net.ingress.IngressChannel;

/**
 * Admits the messages an NIO connection frames to its ingress channel, and stops
 * the selector reading that connection while the ingress asks, until it resumes.
 * Messages are admitted on the selector thread; reading resumes from any thread.
 */
final class ReadGate implements Consumer<Message> {

	private final SelectableChannel socket;
	private final Selector selector;
	private volatile IngressChannel channel;

	/** Set when an admission asked to pause. Selector thread only. */
	private boolean pauseAsked;

	ReadGate(SelectableChannel socket, Selector selector) {
		this.socket = socket;
		this.selector = selector;
	}

	void setChannel(IngressChannel channel) {
		this.channel = channel;
	}

	IngressChannel getChannel() {
		return channel;
	}

	@Override
	public void accept(Message m) {
		if (!channel.admit(m)) pauseAsked = true;
	}

	/** Whether to stop reading the socket for now. Selector thread. */
	boolean isPauseAsked() {
		return pauseAsked;
	}

	/** After a read: stops reading if the ingress still asks. Selector thread. */
	synchronized void afterRead() {
		if (!pauseAsked) return;
		pauseAsked = false;
		// A resume since the admission that asked has already left reading on
		if (channel.isPaused()) setReading(false);
	}

	/** Resumes reading. Any thread. */
	synchronized void resume() {
		setReading(true);
		selector.wakeup();
	}

	private void setReading(boolean on) {
		SelectionKey key = socket.keyFor(selector);
		if (key == null) return;
		try {
			// Atomic: the sender changes write interest from other threads
			if (on) {
				key.interestOpsOr(SelectionKey.OP_READ);
			} else {
				key.interestOpsAnd(~SelectionKey.OP_READ);
			}
		} catch (CancelledKeyException e) {
			// Closed meanwhile: nothing more to read
		}
	}
}
