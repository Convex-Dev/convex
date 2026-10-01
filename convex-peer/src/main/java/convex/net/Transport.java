package convex.net;

import java.io.IOException;
import java.net.URI;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

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
}
