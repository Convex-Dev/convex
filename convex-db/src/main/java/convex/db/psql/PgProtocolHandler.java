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
		} else if (msg instanceof PgMessageDecoder.Parse parse) {
			handleParse(ctx, parse);
		} else if (msg instanceof PgMessageDecoder.Bind bind) {
			handleBind(ctx, bind);
		} else if (msg instanceof PgMessageDecoder.Describe describe) {
			handleDescribe(ctx, describe);
		} else if (msg instanceof PgMessageDecoder.Execute execute) {
			handleExecute(ctx, execute);
		} else if (msg instanceof PgMessageDecoder.Close close) {
			handleClose(ctx, close);
		} else if (msg instanceof PgMessageDecoder.Flush) {
			ctx.flush();
		} else {
			log.warn("Unhandled message: {}", msg.getClass().getSimpleName());
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

		String fullSql = query.sql().trim();
		log.debug("Query: {}", fullSql);

		if (fullSql.isEmpty()) {
			write(ctx, new EmptyQueryResponse());
			write(ctx, ReadyForQuery.IDLE_INSTANCE);
			ctx.flush();
			return;
		}

		// Split on semicolons and execute each statement
		// Note: This is a simple split - doesn't handle semicolons in strings
		String[] statements = fullSql.split(";");

		for (String sql : statements) {
			sql = sql.trim();
			if (sql.isEmpty()) {
				continue;
			}

			// Handle special commands
			if (sql.toUpperCase().startsWith("SET ")) {
				// Ignore SET commands for now
				write(ctx, CommandComplete.set());
				continue;
			}

			try {
				executeQuery(ctx, sql);
			} catch (SQLException e) {
				log.warn("Query error: {}", e.getMessage());
				write(ctx, ErrorResponse.fromException(e));
				// Stop processing on error
				break;
			} catch (Exception e) {
				log.error("Unexpected error", e);
				write(ctx, ErrorResponse.fromException(e));
				break;
			}
		}

		write(ctx, ReadyForQuery.IDLE_INSTANCE);
		ctx.flush();
	}

	private void executeQuery(ChannelHandlerContext ctx, String sql) throws SQLException {
		sql = rewriteQuery(sql);

		// Null means return empty result (e.g., for system catalog queries)
		if (sql == null) {
			write(ctx, CommandComplete.select(0));
			return;
		}

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

	/**
	 * Rewrites PostgreSQL-specific SQL to Calcite-compatible syntax.
	 * Returns null if the query should return an empty result set.
	 */
	private String rewriteQuery(String sql) {
		if (sql == null) return sql;

		sql = sql.trim();
		String lowerSql = sql.toLowerCase();

		// System catalog queries - return null to signal empty result
		if (lowerSql.contains("pg_catalog.") ||
			lowerSql.contains("pg_type") ||
			lowerSql.contains("pg_class") ||
			lowerSql.contains("pg_namespace") ||
			lowerSql.contains("pg_attribute") ||
			lowerSql.contains("pg_constraint") ||
			lowerSql.contains("pg_index") ||
			lowerSql.contains("pg_proc") ||
			lowerSql.contains("pg_database") ||
			lowerSql.contains("pg_tables") ||
			lowerSql.contains("pg_views") ||
			lowerSql.contains("information_schema.")) {
			return null; // Signal to return empty result
		}

		// CURRENT_SCHEMA() or CURRENT_SCHEMA -> 'public'
		sql = sql.replaceAll("(?i)CURRENT_SCHEMA\\s*\\(\\s*\\)", "'public'");
		sql = sql.replaceAll("(?i)\\bCURRENT_SCHEMA\\b", "'public'");

		// CURRENT_DATABASE() -> 'database_name'
		sql = sql.replaceAll("(?i)CURRENT_DATABASE\\s*\\(\\s*\\)", "'" + database + "'");

		// CURRENT_USER -> 'user'
		sql = sql.replaceAll("(?i)\\bCURRENT_USER\\b", "'convex'");

		// SESSION_USER -> 'user'
		sql = sql.replaceAll("(?i)\\bSESSION_USER\\b", "'convex'");

		// version() -> '15.0 (Convex)'
		sql = sql.replaceAll("(?i)\\bversion\\s*\\(\\s*\\)", "'PostgreSQL 15.0 (Convex SQL)'");

		// pg_backend_pid() -> process ID
		sql = sql.replaceAll("(?i)pg_backend_pid\\s*\\(\\s*\\)", String.valueOf(processId));

		// PostgreSQL cast syntax ::type -> CAST(... AS type)
		// This is complex to do with regex, so just handle common cases
		sql = sql.replaceAll("::integer", "");
		sql = sql.replaceAll("::int", "");
		sql = sql.replaceAll("::text", "");
		sql = sql.replaceAll("::varchar", "");
		sql = sql.replaceAll("::regclass", "");

		return sql;
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

	// ========== Extended Query Protocol ==========

	private final Map<String, PreparedStatement> preparedStatements = new java.util.HashMap<>();
	private final Map<String, String> preparedQueries = new java.util.HashMap<>();
	private ResultSet currentResultSet;
	private ResultSetMetaData currentMetaData;

	private void handleParse(ChannelHandlerContext ctx, PgMessageDecoder.Parse parse) {
		if (!authenticated) {
			sendErrorAndClose(ctx, "28000", "not authenticated");
			return;
		}

		try {
			String name = parse.name();
			String query = parse.query();
			log.debug("Parse: name='{}', query='{}'", name, query);

			// Store the query for later execution
			preparedQueries.put(name, query);

			// Send ParseComplete
			write(ctx, ParseComplete.INSTANCE);
		} catch (Exception e) {
			log.warn("Parse error: {}", e.getMessage());
			write(ctx, ErrorResponse.fromException(e));
		}
	}

	private void handleBind(ChannelHandlerContext ctx, PgMessageDecoder.Bind bind) {
		if (!authenticated) {
			sendErrorAndClose(ctx, "28000", "not authenticated");
			return;
		}

		log.debug("Bind: portal='{}', statement='{}'", bind.portal(), bind.statement());

		// For now, just acknowledge - we'll execute on Execute
		write(ctx, BindComplete.INSTANCE);
	}

	private void handleDescribe(ChannelHandlerContext ctx, PgMessageDecoder.Describe describe) {
		if (!authenticated) {
			sendErrorAndClose(ctx, "28000", "not authenticated");
			return;
		}

		log.debug("Describe: type={}, name='{}'", (char) describe.type(), describe.name());

		try {
			if (describe.type() == 'S') {
				// Describe prepared statement
				String query = preparedQueries.get(describe.name());
				if (query == null) {
					query = preparedQueries.get(""); // unnamed statement
				}

				// For now, send empty ParameterDescription and NoData or RowDescription
				write(ctx, ParameterDescription.EMPTY);

				if (query != null && query.toUpperCase().trim().startsWith("SELECT")) {
					// Execute to get metadata
					try (Statement stmt = connection.createStatement();
						 ResultSet rs = stmt.executeQuery(query)) {
						write(ctx, RowDescription.fromMetaData(rs.getMetaData()));
					}
				} else {
					write(ctx, NoData.INSTANCE);
				}
			} else {
				// Describe portal - send NoData for now
				write(ctx, NoData.INSTANCE);
			}
		} catch (Exception e) {
			log.warn("Describe error: {}", e.getMessage());
			write(ctx, ErrorResponse.fromException(e));
		}
	}

	private void handleExecute(ChannelHandlerContext ctx, PgMessageDecoder.Execute execute) {
		if (!authenticated) {
			sendErrorAndClose(ctx, "28000", "not authenticated");
			return;
		}

		log.debug("Execute: portal='{}', maxRows={}", execute.portal(), execute.maxRows());

		try {
			// Get the query from the unnamed statement/portal
			String query = preparedQueries.get("");
			if (query == null) {
				query = preparedQueries.get(execute.portal());
			}

			if (query == null || query.trim().isEmpty()) {
				write(ctx, new EmptyQueryResponse());
				return;
			}

			executeQuery(ctx, query);
		} catch (SQLException e) {
			log.warn("Execute error: {}", e.getMessage());
			write(ctx, ErrorResponse.fromException(e));
		} catch (Exception e) {
			log.error("Unexpected error during execute", e);
			write(ctx, ErrorResponse.fromException(e));
		}
	}

	private void handleClose(ChannelHandlerContext ctx, PgMessageDecoder.Close close) {
		log.debug("Close: type={}, name='{}'", (char) close.type(), close.name());

		if (close.type() == 'S') {
			preparedStatements.remove(close.name());
			preparedQueries.remove(close.name());
		}

		write(ctx, CloseComplete.INSTANCE);
	}

	private void sendErrorAndClose(ChannelHandlerContext ctx, String code, String message) {
		write(ctx, ErrorResponse.builder()
			.severity("FATAL")
			.code(code)
			.message(message)
			.build());
		ctx.flush();
		ctx.close();
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
