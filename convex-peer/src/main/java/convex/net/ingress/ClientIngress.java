package convex.net.ingress;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.core.message.AConnection;
import convex.core.util.Shutdown;

/**
 * The ingress shared by the network connections this process opens, whatever their
 * transport. A connection whose remote peer has proved its key is served in its
 * priority lane.
 */
public final class ClientIngress {

	private static final Logger log = LoggerFactory.getLogger(ClientIngress.class.getName());

	private static volatile Ingress ingress = null;

	private ClientIngress() {
	}

	/**
	 * Gets the client ingress, starting it on first use. Its workers are daemon
	 * threads, and it closes when the process shuts down its connections.
	 *
	 * @return Client ingress
	 */
	public static Ingress get() {
		Ingress current = ingress;
		if (current != null) return current;

		synchronized (ClientIngress.class) {
			if (ingress != null) return ingress;
			ingress = new Ingress("Client", IngressLimits.defaults(), AConnection::isTrusted,
				e -> log.error("JVM failure in client ingress", e));
			Shutdown.addHook(Shutdown.CONNECTION, () -> {
				Ingress closing = ingress;
				ingress = null;
				if (closing != null) closing.close();
			});
			return ingress;
		}
	}
}
