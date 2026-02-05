package convex.db.psql;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * PostgreSQL wire protocol server backed by Convex SQL.
 *
 * <p>This server allows PostgreSQL clients (psql, JDBC drivers, etc.) to
 * connect and execute SQL queries against a Convex database.
 *
 * <p>Usage:
 * <pre>
 * // Create and register a Convex database
 * SQLDatabase db = SQLDatabase.create("mydb", keyPair);
 * ConvexSchemaFactory.register("mydb", db);
 *
 * // Start the PostgreSQL server
 * PgServer server = PgServer.builder()
 *     .port(5432)
 *     .database("mydb")
 *     .build();
 * server.start();
 *
 * // Connect with psql:
 * // psql -h localhost -p 5432 -d mydb
 * </pre>
 */
public class PgServer {

	private static final Logger log = LoggerFactory.getLogger(PgServer.class);

	private int port;
	private final String database;
	private final String password;
	private final Supplier<Connection> connectionSupplier;

	private EventLoopGroup bossGroup;
	private EventLoopGroup workerGroup;
	private Channel serverChannel;
	private final AtomicBoolean running = new AtomicBoolean(false);

	private PgServer(Builder builder) {
		this.port = builder.port;
		this.database = builder.database;
		this.password = builder.password;

		if (builder.connectionSupplier != null) {
			this.connectionSupplier = builder.connectionSupplier;
		} else {
			// Default: connect via Convex JDBC driver
			this.connectionSupplier = () -> {
				try {
					return DriverManager.getConnection("jdbc:convex:database=" + database);
				} catch (SQLException e) {
					throw new RuntimeException("Failed to connect to database: " + database, e);
				}
			};
		}
	}

	/**
	 * Starts the PostgreSQL server.
	 */
	public void start() throws InterruptedException {
		if (!running.compareAndSet(false, true)) {
			throw new IllegalStateException("Server is already running");
		}

		// Use daemon threads so JVM can exit cleanly when server isn't stopped explicitly
		io.netty.util.concurrent.DefaultThreadFactory bossFactory =
			new io.netty.util.concurrent.DefaultThreadFactory("pg-boss", true);
		io.netty.util.concurrent.DefaultThreadFactory workerFactory =
			new io.netty.util.concurrent.DefaultThreadFactory("pg-worker", true);
		bossGroup = new NioEventLoopGroup(1, bossFactory);
		workerGroup = new NioEventLoopGroup(0, workerFactory);

		try {
			ServerBootstrap b = new ServerBootstrap();
			b.group(bossGroup, workerGroup)
				.channel(NioServerSocketChannel.class)
				.childHandler(new ChannelInitializer<SocketChannel>() {
					@Override
					protected void initChannel(SocketChannel ch) {
						ch.pipeline().addLast(
							new PgMessageDecoder(),
							new PgProtocolHandler(connectionSupplier, password)
						);
					}
				})
				.option(ChannelOption.SO_BACKLOG, 128)
				.option(ChannelOption.SO_REUSEADDR, true)
				.childOption(ChannelOption.SO_KEEPALIVE, true)
				.childOption(ChannelOption.TCP_NODELAY, true);

			ChannelFuture f = b.bind(port).sync();
			serverChannel = f.channel();

			// Capture actual port (useful when binding to port 0)
			java.net.InetSocketAddress addr = (java.net.InetSocketAddress) serverChannel.localAddress();
			this.port = addr.getPort();

			log.info("PostgreSQL server started on port {}", this.port);
			log.info("Database: {}", database);
			log.info("Connect with: psql -h localhost -p {} -d {}", port, database);

		} catch (Exception e) {
			running.set(false);
			shutdown();
			throw e;
		}
	}

	/**
	 * Starts the server and blocks until it's closed.
	 */
	public void startAndWait() throws InterruptedException {
		start();
		serverChannel.closeFuture().sync();
	}

	/**
	 * Stops the server.
	 */
	public void stop() {
		if (running.compareAndSet(true, false)) {
			log.info("Stopping PostgreSQL server...");
			shutdown();
		}
	}

	private void shutdown() {
		if (serverChannel != null) {
			try {
				serverChannel.close().await(5, java.util.concurrent.TimeUnit.SECONDS);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
			serverChannel = null;
		}
		if (workerGroup != null) {
			try {
				workerGroup.shutdownGracefully(0, 2, java.util.concurrent.TimeUnit.SECONDS)
					.await(3, java.util.concurrent.TimeUnit.SECONDS);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
			workerGroup = null;
		}
		if (bossGroup != null) {
			try {
				bossGroup.shutdownGracefully(0, 2, java.util.concurrent.TimeUnit.SECONDS)
					.await(3, java.util.concurrent.TimeUnit.SECONDS);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
			bossGroup = null;
		}
	}

	/**
	 * Returns true if the server is running.
	 */
	public boolean isRunning() {
		return running.get();
	}

	/**
	 * Returns the port the server is listening on.
	 */
	public int getPort() {
		return port;
	}

	/**
	 * Returns a new builder for creating a PgServer.
	 */
	public static Builder builder() {
		return new Builder();
	}

	/**
	 * Builder for PgServer.
	 */
	public static class Builder {
		private int port = 5432;
		private String database = "convex";
		private String password = null;
		private Supplier<Connection> connectionSupplier = null;

		/**
		 * Sets the port to listen on. Default is 5432.
		 */
		public Builder port(int port) {
			this.port = port;
			return this;
		}

		/**
		 * Sets the database name. Default is "convex".
		 */
		public Builder database(String database) {
			this.database = database;
			return this;
		}

		/**
		 * Sets a password for authentication. If null, trust authentication is used.
		 */
		public Builder password(String password) {
			this.password = password;
			return this;
		}

		/**
		 * Sets a custom connection supplier. If not set, connections are
		 * obtained via the Convex JDBC driver.
		 */
		public Builder connectionSupplier(Supplier<Connection> supplier) {
			this.connectionSupplier = supplier;
			return this;
		}

		/**
		 * Builds the PgServer.
		 */
		public PgServer build() {
			return new PgServer(this);
		}
	}

	/**
	 * Main entry point for running the server standalone.
	 */
	public static void main(String[] args) throws Exception {
		int port = 5432;
		String database = "convex";

		// Parse command line arguments
		for (int i = 0; i < args.length; i++) {
			switch (args[i]) {
				case "-p", "--port" -> port = Integer.parseInt(args[++i]);
				case "-d", "--database" -> database = args[++i];
				case "-h", "--help" -> {
					System.out.println("Usage: PgServer [-p port] [-d database]");
					System.out.println("  -p, --port      Port to listen on (default: 5432)");
					System.out.println("  -d, --database  Database name (default: convex)");
					return;
				}
			}
		}

		PgServer server = PgServer.builder()
			.port(port)
			.database(database)
			.build();

		// Add shutdown hook
		Runtime.getRuntime().addShutdownHook(new Thread(server::stop));

		server.startAndWait();
	}
}
