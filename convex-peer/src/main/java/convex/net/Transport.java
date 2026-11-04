package convex.net;

import java.io.IOException;
import java.net.URI;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

import convex.core.Result;
import convex.core.data.ACell;
import convex.core.message.AConnection;
import convex.core.message.Message;

/**
 * Opens a message connection to an endpoint. A transport owns wire delivery;
 * request correlation and peer verification remain above {@link AConnection}.
 *
 * <p>The receive limit must apply before delivery starts. Return only once the
 * connection is ready, including any transport handshake. Implementations must
 * close resources on failure. A URI retains transport-specific paths and options
 * so future message transports need not reduce their endpoints to socket addresses.</p>
 */
@FunctionalInterface
public interface Transport {

	AConnection connect(URI endpoint, Consumer<Message> receiveAction, int maxMessageLength)
			throws IOException, InterruptedException, TimeoutException;

	/**
	 * Opens a connection whose delivery may ask to wait by returning a retry, as a
	 * server's delivery does when a queue is full. A transport that can pause reading
	 * stops reading this connection until the retry accepts the message or gives up,
	 * and then answers a request it gave up on with :LOAD.
	 *
	 * <p>This default cannot pause, and never waits: a message whose delivery asks to
	 * wait is answered with :LOAD at once if it is a request, and dropped otherwise.
	 * Messages that need no wait are delivered as by {@link #connect}.</p>
	 */
	default AConnection connectWithDelivery(URI endpoint, Function<Message, Predicate<Message>> delivery,
			int maxMessageLength) throws IOException, InterruptedException, TimeoutException {
		return connect(endpoint, withoutPausing(delivery), maxMessageLength);
	}

	/**
	 * Adapts a delivery for a receive path that cannot pause: a message it asks to
	 * wait for is answered with :LOAD if it is a request, and dropped otherwise.
	 *
	 * @param delivery delivery that may return a retry
	 * @return receive action that never waits
	 */
	static Consumer<Message> withoutPausing(Function<Message, Predicate<Message>> delivery) {
		return message -> {
			if (delivery.apply(message)==null) return;
			ACell id=message.getRequestID();
			if (id!=null && message.getConnection()!=null) {
				message.returnMessage(Message.createResult(Result.SERVER_LOADED.withID(id)));
			}
		};
	}
}
