package convex.dlfs;

import java.io.Closeable;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

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
 * Standalone DLFS WebDAV server with multi-drive support.
 *
 * <p>Creates a lightweight Javalin HTTP server with a {@link DLFSWebDAV}
 * handler and optional Ed25519 JWT bearer token authentication via
 * {@link AuthMiddleware}.
 *
 * <p>Each authenticated user gets their own set of named drives. Drives
 * appear as top-level directories under {@code /dlfs/}.
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
	private final AKeyPair keyPair;
	private String bindHost = DEFAULT_BIND_HOST;
	private long maxRequestSize = DEFAULT_MAX_REQUEST_SIZE;
	private Javalin app;

	private DLFSServer(DLFSDriveManager driveManager, AKeyPair keyPair) {
		this.driveManager = driveManager;
		this.keyPair = keyPair;
		this.webdav = new DLFSWebDAV(driveManager);
		// A configured authentication key should never silently leave mutations open.
		this.webdav.setRequireAuthForWrites(keyPair != null);
		this.mcpServer = new McpServer(Maps.of(
			"name", "dlfs-mcp",
			"title", "DLFS MCP",
			"version", Utils.getVersion()
		));
		this.mcpTools = new DlfsMcpTools(driveManager).setRequireAuthForWrites(keyPair != null);
		mcpTools.registerAll(mcpServer);
	}

	/**
	 * Creates a DLFS server with a fresh drive manager.
	 *
	 * @param keyPair Ed25519 key pair for auth (null for no auth)
	 * @return New DLFSServer instance
	 */
	public static DLFSServer create(AKeyPair keyPair) {
		return new DLFSServer(new DLFSDriveManager(), keyPair);
	}

	/**
	 * Creates a DLFS server using an application-supplied drive manager.
	 *
	 * @param driveManager Drive registry and filesystem view manager
	 * @param keyPair Ed25519 key pair for auth (null for no auth)
	 * @return New DLFSServer instance
	 */
	public static DLFSServer create(DLFSDriveManager driveManager, AKeyPair keyPair) {
		if (driveManager==null) throw new IllegalArgumentException("Drive manager cannot be null");
		return new DLFSServer(driveManager, keyPair);
	}

	/**
	 * Creates a DLFS server with a single filesystem exposed as a named drive.
	 * Useful for backwards-compatible testing with a single drive.
	 *
	 * @param fs        The filesystem to serve
	 * @param driveName The drive name to use
	 * @param identity  The owner identity (null for anonymous)
	 * @param keyPair   Ed25519 key pair for auth (null for no auth)
	 * @return New DLFSServer instance
	 */
	public static DLFSServer create(FileSystem fs, String driveName, String identity, AKeyPair keyPair) {
		DLFSDriveManager dm = new DLFSDriveManager();
		dm.seedDrive(identity, driveName, fs);
		return new DLFSServer(dm, keyPair);
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
			if (keyPair != null) {
				PeerAuth peerAuth = PeerAuth.createWithDIDAudience(keyPair);
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

	/**
	 * Launches a standalone DLFS WebDAV server for local testing.
	 *
	 * <p>Usage: {@code java convex.dlfs.DLFSServer [port]}
	 * <p>Default port is 8080. No authentication required.
	 * <p>Seeds a "home" drive with a test.txt file.
	 */
	public static void main(String[] args) {
		int port = 8080;
		if (args.length > 0) {
			try {
				port = Integer.parseInt(args[0]);
			} catch (NumberFormatException e) {
				System.err.println("Usage: DLFSServer [port]");
				System.exit(1);
			}
		}

		DLFSServer server = DLFSServer.create(null);

		// Seed a "home" drive with a demo file
		DLFSDriveManager dm = server.getDriveManager();
		dm.createDrive(null, "home");
		FileSystem homeFs = dm.getDrive(null, "home");
		try {
			Path testFile = homeFs.getPath("/test.txt");
			Files.write(testFile, "Hello from DLFS!\n".getBytes(),
					StandardOpenOption.CREATE,
					StandardOpenOption.WRITE);
		} catch (Exception e) {
			System.err.println("Warning: could not seed demo file: " + e.getMessage());
		}

		server.start(port);

		System.out.println("DLFS WebDAV server running on http://localhost:" + server.getPort() + "/dlfs/");
		System.out.println("Drive 'home' seeded with test.txt");
		System.out.println("No authentication required.");
		System.out.println("Press Ctrl+C to stop.");

		Runtime.getRuntime().addShutdownHook(new Thread(server::close));
	}
}
