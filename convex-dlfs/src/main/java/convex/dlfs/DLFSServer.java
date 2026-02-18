package convex.dlfs;

import java.io.Closeable;
import java.nio.file.FileSystem;

import org.eclipse.jetty.server.ServerConnector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.core.crypto.AKeyPair;
import convex.lattice.fs.DLFS;
import convex.peer.auth.PeerAuth;
import convex.restapi.auth.AuthMiddleware;
import io.javalin.Javalin;

/**
 * Standalone DLFS WebDAV server.
 *
 * <p>Creates a lightweight Javalin HTTP server with a {@link DLFSWebDAV}
 * handler and optional Ed25519 JWT bearer token authentication via
 * {@link AuthMiddleware}.
 */
public class DLFSServer implements Closeable {

	private static final Logger log = LoggerFactory.getLogger(DLFSServer.class);

	private final FileSystem fs;
	private final DLFSWebDAV webdav;
	private final AKeyPair keyPair;
	private Javalin app;

	private DLFSServer(FileSystem fs, AKeyPair keyPair) {
		this.fs = fs;
		this.keyPair = keyPair;
		this.webdav = new DLFSWebDAV(fs);
	}

	/**
	 * Creates a DLFS server with a new local filesystem.
	 *
	 * @param keyPair Ed25519 key pair for auth (null for no auth)
	 * @return New DLFSServer instance
	 */
	public static DLFSServer create(AKeyPair keyPair) {
		return create(DLFS.createLocal(), keyPair);
	}

	/**
	 * Creates a DLFS server with the given filesystem.
	 *
	 * @param fs      The filesystem to serve via WebDAV
	 * @param keyPair Ed25519 key pair for auth (null for no auth)
	 * @return New DLFSServer instance
	 */
	public static DLFSServer create(FileSystem fs, AKeyPair keyPair) {
		return new DLFSServer(fs, keyPair);
	}

	/**
	 * Starts the server on the specified port.
	 *
	 * @param port Port number (0 for random)
	 */
	public void start(int port) {
		app = Javalin.create(config -> {
			config.bundledPlugins.enableCors(cors -> {
				cors.addRule(corsConfig -> corsConfig.anyHost());
			});
			config.useVirtualThreads = true;
		});

		// Wire auth middleware if key pair provided
		if (keyPair != null) {
			PeerAuth peerAuth = new PeerAuth(keyPair);
			AuthMiddleware auth = new AuthMiddleware(peerAuth);
			app.before(auth.handler());
		}

		// Request/response logging
		app.before(ctx -> {
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
		app.after(ctx -> {
			log.debug("<-- {} {} {}", ctx.status(), ctx.req().getMethod(), ctx.req().getRequestURI());
		});

		// Register WebDAV routes
		webdav.addRoutes(app);

		// Configure Jetty port
		org.eclipse.jetty.server.Server jettyServer = app.jettyServer().server();
		ServerConnector connector = new ServerConnector(jettyServer);
		connector.setPort(port);
		jettyServer.addConnector(connector);

		app.start();
	}

	/**
	 * Gets the port the server is listening on.
	 */
	public int getPort() {
		if (app == null) throw new IllegalStateException("Server not started");
		return app.port();
	}

	/**
	 * Gets the filesystem being served.
	 */
	public FileSystem getFileSystem() {
		return fs;
	}

	/**
	 * Gets the WebDAV handler.
	 */
	public DLFSWebDAV getWebDAV() {
		return webdav;
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
	 * <p>Default port is 8080. No authentication is required.
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
		server.start(port);

		// Seed a demo file so reads can be verified immediately
		try {
			java.nio.file.FileSystem fs = server.getFileSystem();
			java.nio.file.Path testFile = fs.getPath("/test.txt");
			java.nio.file.Files.write(testFile, "Hello from DLFS!\n".getBytes(),
					java.nio.file.StandardOpenOption.CREATE,
					java.nio.file.StandardOpenOption.WRITE);
		} catch (Exception e) {
			System.err.println("Warning: could not seed demo file: " + e.getMessage());
		}

		System.out.println("DLFS WebDAV server running on http://localhost:" + server.getPort() + "/dlfs/");
		System.out.println("No authentication required.");
		System.out.println("Press Ctrl+C to stop.");

		Runtime.getRuntime().addShutdownHook(new Thread(server::close));
	}
}
