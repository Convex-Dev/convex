package convex.restapi.handler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.eclipse.jetty.server.AbstractConnector;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.GracefulHandler;
import org.junit.jupiter.api.Test;

import io.javalin.Javalin;

/**
 * A stopping server must drain requests in flight rather than cut them off (#777).
 * Jetty only does so with a stop timeout and a {@link GracefulHandler} installed.
 */
public class GracefulStopTest {

	@Test
	public void testInstallEnablesGracefulStop() {
		Javalin app = Javalin.create(config -> {
			config.jetty.addConnector((server, http) -> {
				ServerConnector connector = new ServerConnector(server, 1, 1);
				connector.setPort(0);
				return connector;
			});
			GracefulStop.install(config);
		}).start();
		try {
			Server server = app.jettyServer().server();
			assertEquals(GracefulStop.STOP_TIMEOUT, server.getStopTimeout());
			assertNotNull(server.getDescendant(GracefulHandler.class),
				"the started handler tree must include a GracefulHandler");
			for (Connector connector : server.getConnectors()) {
				assertEquals(GracefulStop.SHUTDOWN_IDLE_TIMEOUT,
					((AbstractConnector) connector).getShutdownIdleTimeout(),
					"idle keep-alive connections must not hold the stop");
			}
		} finally {
			app.stop();
		}
	}
}
