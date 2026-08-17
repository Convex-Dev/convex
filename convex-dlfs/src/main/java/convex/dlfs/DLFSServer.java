package convex.dlfs;

import java.io.Closeable;

import org.eclipse.jetty.server.ServerConnector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.core.crypto.AKeyPair;
import convex.core.data.Maps;
import convex.core.util.Utils;
import convex.peer.auth.PeerAuth;
import convex.restapi.auth.AuthMiddleware;
import convex.restapi.handler.HttpMethodFilter;
import convex.restapi.mcp.McpServer;
import io.javalin.Javalin;
import io.javalin.config.RoutesConfig;

/**
 * WebDAV and MCP transport for locally routed DLFS drives.
 *
 * <p>Creates a lightweight Javalin HTTP server with a {@link DLFSWebDAV}
 * handler and optional Ed25519 JWT bearer token authentication via
 * {@link AuthMiddleware}.
 *
 * <p>The supplied {@link DLFSDriveManager} determines which identities reach
 * which detached or lattice-backed drive collections. Drives appear as
 * top-level directories under {@code /dlfs/}. This server owns only its HTTP
 * lifecycle; application sync, durability and store lifecycle remain bootstrap
 * policy.</p>
 */
public class DLFSServer implements Closeable {
	/** Default bind host: DLFS is private/local unless an application explicitly exposes it. */
	public static final String DEFAULT_BIND_HOST = "127.0.0.1";
	/** Default upper bound for any HTTP request body. */
	public static final long DEFAULT_MAX_REQUEST_SIZE = 64L * 1024 * 1024;

	private static final Logger log = LoggerFactory.getLogger(DLFSServer.class);

	private final DLFSDriveManager driveManager;
	private final DLFSWebDAV webdav;
	private final McpServer mcpServer;
	private final DlfsMcpTools mcpTools;
	private final PeerAuth peerAuth;
	private String bindHost = DEFAULT_BIND_HOST;
	private long maxRequestSize = DEFAULT_MAX_REQUEST_SIZE;
	private Javalin app;

	private DLFSServer(DLFSDriveManager driveManager, PeerAuth peerAuth) {
		this.driveManager = driveManager;
		this.peerAuth = peerAuth;
		this.webdav = new DLFSWebDAV(driveManager);
		// Configured authentication should never silently leave mutations open.
		this.webdav.setRequireAuthForWrites(peerAuth != null);
		this.mcpServer = new McpServer(Maps.of(
			"name", "dlfs-mcp",
			"title", "DLFS MCP",
			"version", Utils.getVersion()
		));
		this.mcpTools = new DlfsMcpTools(driveManager).setRequireAuthForWrites(peerAuth != null);
		mcpTools.registerAll(mcpServer);
	}

	/**
	 * Creates an unauthenticated DLFS transport over application-supplied routing.
	 *
	 * @param driveManager Local identity and drive routing policy
	 * @return New DLFSServer instance
	 */
	public static DLFSServer create(DLFSDriveManager driveManager) {
		if (driveManager==null) throw new IllegalArgumentException("Drive manager cannot be null");
		return new DLFSServer(driveManager,null);
	}

	/**
	 * Creates an authenticated DLFS transport over application-supplied routing.
	 *
	 * @param driveManager Local identity and drive routing policy
	 * @param peerAuth Authentication verifier and audience policy
	 * @return New DLFSServer instance
	 */
	public static DLFSServer createAuthenticated(DLFSDriveManager driveManager, PeerAuth peerAuth) {
		if (driveManager==null) throw new IllegalArgumentException("Drive manager cannot be null");
		if (peerAuth==null) throw new IllegalArgumentException("Authentication policy cannot be null");
		return new DLFSServer(driveManager,peerAuth);
	}

	/**
	 * Creates an authenticated DLFS transport whose audience is the supplied key.
	 *
	 * <p>The key configures HTTP authentication only. It is not implicitly used as
	 * a lattice signing key; bootstrap code may choose the same key for both roles.</p>
	 *
	 * @param driveManager Local identity and drive routing policy
	 * @param audienceKey Key defining the accepted authentication audience
	 * @return New DLFSServer instance
	 */
	public static DLFSServer createWithAudience(DLFSDriveManager driveManager,
			AKeyPair audienceKey) {
		if (audienceKey==null) throw new IllegalArgumentException("Authentication audience key cannot be null");
		return createAuthenticated(driveManager,PeerAuth.createWithDIDAudience(audienceKey));
	}

	/**
	 * Creates an explicitly process-local server for tests and demonstrations.
	 *
	 * @return Unauthenticated server with a detached in-memory drive registry
	 */
	public static DLFSServer createEphemeral() {
		return create(DLFSDriveManager.createEphemeral());
	}

	/**
	 * Creates an explicitly process-local authenticated server.
	 *
	 * @param audienceKey Key defining the accepted authentication audience
	 * @return Authenticated server with a detached in-memory drive registry
	 */
	public static DLFSServer createEphemeralWithAudience(AKeyPair audienceKey) {
		return createWithAudience(DLFSDriveManager.createEphemeral(),audienceKey);
	}

	/**
	 * Starts the server on the specified port.
	 *
	 * @param port Port number (0 for random)
	 */
	public void start(int port) {
		if (app != null) throw new IllegalStateException("Server already started");
		Javalin newApp = Javalin.create(config -> {
			config.http.maxRequestSize = maxRequestSize;
			config.concurrency.useVirtualThreads = true;

			HttpMethodFilter.install(config);

			// Configure Jetty connector with minimal platform threads.
			// Request handling uses virtual threads (useVirtualThreads=true above),
			// so we only need 1 acceptor + 1 selector for the connector.
			config.jetty.addConnector((jettyServer, httpConfig) -> {
				ServerConnector connector = new ServerConnector(jettyServer, 1, 1);
				connector.setHost(bindHost);
				connector.setPort(port);
				return connector;
			});

			RoutesConfig routes = config.routes;
			routes.exception(IllegalArgumentException.class, (e, ctx) ->
				ctx.status(400).result("Bad Request: " + e.getMessage()));

			// Wire auth middleware if key pair provided (with audience checking)
			if (peerAuth != null) {
				AuthMiddleware auth = new AuthMiddleware(peerAuth);
				routes.before(auth.handler());
			}

			// Request/response logging
			routes.before(ctx -> {
				if (!log.isDebugEnabled()) return;
				String method = ctx.req().getMethod();
				String uri = ctx.req().getRequestURI();
				String dest = ctx.header("Destination");
				String depth = ctx.header("Depth");
				StringBuilder sb = new StringBuilder();
				sb.append("--> ").append(method).append(" ").append(uri);
				if (dest != null) sb.append("  Destination: ").append(dest);
				if (depth != null) sb.append("  Depth: ").append(depth);
				log.debug(sb.toString());
			});
			routes.after(ctx -> {
				log.debug("<-- {} {} {}", ctx.status(), ctx.req().getMethod(), ctx.req().getRequestURI());
			});

			// Register WebDAV and MCP routes
			webdav.addRoutes(routes);
			mcpServer.addRoutes(routes);
		});

		newApp.start();
		app = newApp;
	}

	/**
	 * Sets the network interface/address to bind. Must be called before start.
	 * The default is {@value #DEFAULT_BIND_HOST}; exposing DLFS is an explicit choice.
	 */
	public DLFSServer setBindHost(String host) {
		if (app != null) throw new IllegalStateException("Server already started");
		if (host == null || host.isBlank()) throw new IllegalArgumentException("Bind host is required");
		this.bindHost = host;
		return this;
	}

	/** Sets the maximum accepted HTTP request body size. Must be called before start. */
	public DLFSServer setMaxRequestSize(long bytes) {
		if (app != null) throw new IllegalStateException("Server already started");
		if (bytes <= 0) throw new IllegalArgumentException("Maximum request size must be positive");
		this.maxRequestSize = bytes;
		webdav.setMaxFileSize(bytes);
		return this;
	}

	/**
	 * Gets the port the server is listening on.
	 */
	public int getPort() {
		if (app == null) throw new IllegalStateException("Server not started");
		return app.port();
	}

	/**
	 * Gets the drive manager.
	 */
	public DLFSDriveManager getDriveManager() {
		return driveManager;
	}

	/**
	 * Gets the WebDAV handler.
	 */
	public DLFSWebDAV getWebDAV() {
		return webdav;
	}

	/**
	 * Gets the MCP server. External modules can register additional tools.
	 */
	public McpServer getMcpServer() {
		return mcpServer;
	}

	/** Applies the same mutation-authentication policy to WebDAV and MCP. */
	public DLFSServer setRequireAuthForWrites(boolean require) {
		if (app != null) throw new IllegalStateException("Server already started");
		webdav.setRequireAuthForWrites(require);
		mcpTools.setRequireAuthForWrites(require);
		return this;
	}

	@Override
	public void close() {
		if (app != null) {
			app.stop();
			app = null;
		}
	}

}
