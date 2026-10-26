package convex.restapi.handler;

import org.eclipse.jetty.server.AbstractConnector;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.handler.GracefulHandler;
import org.eclipse.jetty.util.component.LifeCycle;

import io.javalin.config.JavalinConfig;

/**
 * Lets a Javalin server finish the requests in flight when it stops.
 *
 * <p>Jetty skips its graceful phase unless the server has a stop timeout. With
 * one and a {@link GracefulHandler} in the handler tree, stopping closes the
 * connectors, answers new requests with 503 and waits up to the timeout for
 * requests in flight, then stops as before. Owners of long-lived streams must
 * end them before stopping, or each holds the server for the whole timeout.</p>
 *
 * <p>While stopping, Jetty closes a connection once it has been idle for the
 * connector's shutdown idle timeout. Idle keep-alive connections would hold
 * every stop for Jetty's default second, so it is shortened. It applies only to
 * pending I/O: a request whose handler is still working is not cut off.</p>
 */
public final class GracefulStop {

	/** Time allowed for requests in flight to finish, in milliseconds. */
	public static final long STOP_TIMEOUT=5000;

	/** Idle time after which a connection closes while stopping, in milliseconds. */
	public static final long SHUTDOWN_IDLE_TIMEOUT=100;

	private GracefulStop() {}

	/**
	 * Installs a graceful stop on a Javalin server configuration.
	 *
	 * @param config Javalin configuration being built
	 */
	public static void install(JavalinConfig config) {
		config.jetty.modifyServer(server -> {
			server.setStopTimeout(STOP_TIMEOUT);
			server.insertHandler(new GracefulHandler());
			server.addEventListener(new LifeCycle.Listener() {
				@Override
				public void lifeCycleStarting(LifeCycle event) {
					for (Connector connector:server.getConnectors()) {
						if (connector instanceof AbstractConnector c) c.setShutdownIdleTimeout(SHUTDOWN_IDLE_TIMEOUT);
					}
				}
			});
		});
	}
}
