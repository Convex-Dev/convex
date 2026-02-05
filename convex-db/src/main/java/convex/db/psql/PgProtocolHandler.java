package convex.db.psql;

import convex.db.psql.msg.*;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * Handles the PostgreSQL wire protocol and executes SQL queries.
 */
public class PgProtocolHandler extends ChannelInboundHandlerAdapter {

	private static final Logger log = LoggerFactory.getLogger(PgProtocolHandler.class);
	private static final AtomicInteger processIdCounter = new AtomicInteger(1000);

	private final Supplier<Connection> connectionSupplier;
	private final String requiredPassword;

	private Connection connection;
	private String user;
	private String database;
	private int processId;
	private int secretKey;
	private boolean authenticated = false;

	/**
	 * Creates a handler that uses the given connection supplier.
	 *
	 * @param connectionSupplier Supplies JDBC connections for query execution
	 * @param requiredPassword Password required for authentication, or null for trust auth
	 */
	public PgProtocolHandler(Supplier<Connection> connectionSupplier, String requiredPassword) {
		this.connectionSupplier = connectionSupplier;
		this.requiredPassword = requiredPassword;
		this.processId = processIdCounter.incrementAndGet();
		this.secretKey = ThreadLocalRandom.current().nextInt();
	}

	@Override
	public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
		if (msg instanceof PgMessageDecoder.SSLRequest) {
			handleSSLRequest(ctx);
		} else if (msg instanceof PgMessageDecoder.StartupMessage startup) {
			handleStartup(ctx, startup);
		} else if (msg instanceof PgMessageDecoder.PasswordMessage pwd) {
			handlePassword(ctx, pwd);
		} else if (msg instanceof PgMessageDecoder.Query query) {
			handleQuery(ctx, query);
		} else if (msg instanceof PgMessageDecoder.Terminate) {
			handleTerminate(ctx);
		} else if (msg instanceof PgMessageDecoder.Sync) {
			handleSync(ctx);
		} else {
			log.debug("Unhandled message: {}", msg.getClass().getSimpleName());
		}
	}

	private void handleSSLRequest(ChannelHandlerContext ctx) {
		// Respond with 'N' - we don't support SSL
		ByteBuf buf = ctx.alloc().buffer(1);
		buf.writeByte('N');
		ctx.writeAndFlush(buf);
	}

	private void handleStartup(ChannelHandlerContext ctx, PgMessageDecoder.StartupMessage startup) {
		log.debug("Startup: version={}.{}, params={}", startup.majorVersion(), startup.minorVersion(), startup.params());

		this.user = startup.params().get("user");
		this.database = startup.params().get("database");

		if (requiredPassword != null && !requiredPassword.isEmpty()) {
			// Request password authentication
			write(ctx, AuthenticationCleartextPassword.INSTANCE);
			ctx.flush();
		} else {
			// Trust authentication
			completeAuthentication(ctx);
		}
	}

	private void handlePassword(ChannelHandlerContext ctx, PgMessageDecoder.PasswordMessage pwd) {
		if (requiredPassword != null && requiredPassword.equals(pwd.password())) {
			completeAuthentication(ctx);
		} else {
			write(ctx, ErrorResponse.authenticationFailed(user));
			ctx.flush();
			ctx.close();
		}
	}

	private void completeAuthentication(ChannelHandlerContext ctx) {
		authenticated = true;

		// Get a connection
		try {
			connection = connectionSupplier.get();
		} catch (Exception e) {
			log.error("Failed to get connection", e);
			write(ctx, ErrorResponse.fromException(e));
			ctx.close();
			return;
		}

		// Send authentication OK
		write(ctx, AuthenticationOk.INSTANCE);

		// Send parameter status messages
		write(ctx, ParameterStatus.serverVersion("15.0 (Convex)"));
		write(ctx, ParameterStatus.clientEncoding("UTF8"));
		write(ctx, ParameterStatus.serverEncoding("UTF8"));
		write(ctx, ParameterStatus.dateStyle("ISO, MDY"));
		write(ctx, ParameterStatus.timeZone("UTC"));
		write(ctx, ParameterStatus.integerDatetimes(true));
		write(ctx, new ParameterStatus("standard_conforming_strings", "on"));

		// Send backend key data
		write(ctx, new BackendKeyData(processId, secretKey));

		// Ready for query
		write(ctx, ReadyForQuery.IDLE_INSTANCE);
		ctx.flush();
	}

	private void handleQuery(ChannelHandlerContext ctx, PgMessageDecoder.Query query) {
		if (!authenticated) {
			write(ctx, ErrorResponse.builder()
				.severity("FATAL")
				.code("28000")
				.message("not authenticated")
				.build());
			ctx.close();
			return;
		}

		String sql = query.sql().trim();
		log.debug("Query: {}", sql);

		if (sql.isEmpty()) {
			write(ctx, new EmptyQueryResponse());
			write(ctx, ReadyForQuery.IDLE_INSTANCE);
			ctx.flush();
			return;
		}

		// Handle special commands
		if (sql.toUpperCase().startsWith("SET ")) {
			// Ignore SET commands for now
			write(ctx, CommandComplete.set());
			write(ctx, ReadyForQuery.IDLE_INSTANCE);
			ctx.flush();
			return;
		}

		try {
			executeQuery(ctx, sql);
		} catch (SQLException e) {
			log.warn("Query error: {}", e.getMessage());
			write(ctx, ErrorResponse.fromException(e));
		} catch (Exception e) {
			log.error("Unexpected error", e);
			write(ctx, ErrorResponse.fromException(e));
		}

		write(ctx, ReadyForQuery.IDLE_INSTANCE);
		ctx.flush();
	}

	private void executeQuery(ChannelHandlerContext ctx, String sql) throws SQLException {
		try (Statement stmt = connection.createStatement()) {
			boolean hasResultSet = stmt.execute(sql);

			if (hasResultSet) {
				try (ResultSet rs = stmt.getResultSet()) {
					sendResultSet(ctx, rs);
				}
			} else {
				int updateCount = stmt.getUpdateCount();
				String upperSql = sql.toUpperCase().trim();

				if (upperSql.startsWith("INSERT")) {
					write(ctx, CommandComplete.insert(updateCount));
				} else if (upperSql.startsWith("UPDATE")) {
					write(ctx, CommandComplete.update(updateCount));
				} else if (upperSql.startsWith("DELETE")) {
					write(ctx, CommandComplete.delete(updateCount));
				} else if (upperSql.startsWith("CREATE")) {
					write(ctx, CommandComplete.createTable());
				} else if (upperSql.startsWith("DROP")) {
					write(ctx, CommandComplete.dropTable());
				} else {
					write(ctx, new CommandComplete("OK"));
				}
			}
		}
	}

	private void sendResultSet(ChannelHandlerContext ctx, ResultSet rs) throws SQLException {
		ResultSetMetaData meta = rs.getMetaData();
		int columnCount = meta.getColumnCount();

		// Send row description
		write(ctx, RowDescription.fromMetaData(meta));

		// Send data rows
		long rowCount = 0;
		while (rs.next()) {
			write(ctx, DataRow.fromResultSet(rs, columnCount));
			rowCount++;
		}

		// Send command complete
		write(ctx, CommandComplete.select(rowCount));
	}

	private void handleSync(ChannelHandlerContext ctx) {
		write(ctx, ReadyForQuery.IDLE_INSTANCE);
		ctx.flush();
	}

	private void handleTerminate(ChannelHandlerContext ctx) {
		log.debug("Client terminated connection");
		closeConnection();
		ctx.close();
	}

	private void write(ChannelHandlerContext ctx, PgMessage msg) {
		ByteBuf buf = ctx.alloc().buffer();
		msg.write(buf);
		ctx.write(buf);
	}

	private void closeConnection() {
		if (connection != null) {
			try {
				connection.close();
			} catch (SQLException e) {
				log.warn("Error closing connection", e);
			}
			connection = null;
		}
	}

	@Override
	public void channelInactive(ChannelHandlerContext ctx) {
		closeConnection();
	}

	@Override
	public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
		log.error("Protocol error", cause);
		closeConnection();
		ctx.close();
	}

	/**
	 * EmptyQueryResponse - sent when an empty query string is received.
	 */
	private static class EmptyQueryResponse extends PgMessage {
		@Override
		public byte getType() {
			return EMPTY_QUERY;
		}

		@Override
		public void write(ByteBuf buf) {
			buf.writeByte(EMPTY_QUERY);
			buf.writeInt(4);
		}
	}
}
