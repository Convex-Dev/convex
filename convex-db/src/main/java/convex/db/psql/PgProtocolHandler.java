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
				log.warn("Query error: {}", e.getMessage(), e);
				write(ctx, ErrorResponse.fromException(e));
				// Stop processing on error
				break;
			} catch (RuntimeException e) {
				// Runtime exceptions from Calcite (type coercion, etc.) are query errors
				log.warn("Query execution error: {}", e.getMessage(), e);
				write(ctx, ErrorResponse.fromException(e));
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
	 *
	 * <p>WARNING: This method contains numerous hacks to work around Calcite's
	 * lack of native PostgreSQL syntax support. Each hack is marked with a TODO
	 * indicating the proper solution.
	 */
	private String rewriteQuery(String sql) {
		if (sql == null) return sql;

		sql = sql.trim();
		String lowerSql = sql.toLowerCase();

		// TODO: Remove hack needed to handle PostgreSQL regex operators (!~, ~, ~*, !~*)
		// Proper fix: Register custom Calcite operators for POSIX regex matching,
		// or use Calcite Babel parser which has built-in PostgreSQL dialect support
		if (sql.contains("!~") || sql.contains("~*") ||
			(sql.contains("~") && !sql.contains("~=") && !sql.contains("~~"))) {
			return null; // Return empty result for regex queries
		}

		// TODO: Remove hack needed to handle queries for unimplemented pg_catalog tables
		// Proper fix: Implement virtual tables for pg_constraint, pg_index, pg_proc,
		// pg_views, pg_settings, pg_am, pg_roles, pg_stat*, and information_schema
		if (lowerSql.contains("pg_constraint") ||
			lowerSql.contains("pg_index") ||
			lowerSql.contains("pg_proc") ||
			lowerSql.contains("pg_views") ||
			lowerSql.contains("pg_settings") ||
			lowerSql.contains("pg_am") ||
			lowerSql.contains("pg_roles") ||
			lowerSql.contains("pg_stat") ||
			lowerSql.contains("information_schema.")) {
			return null; // Signal to return empty result
		}

		// TODO: Remove hack needed to emulate PostgreSQL search_path behavior
		// Proper fix: Implement Calcite's SchemaPlus.setPath() to configure search path,
		// or use a custom SqlValidator that resolves unqualified table names
		sql = addPgCatalogPrefix(sql, "pg_database");
		sql = addPgCatalogPrefix(sql, "pg_type");
		sql = addPgCatalogPrefix(sql, "pg_class");
		sql = addPgCatalogPrefix(sql, "pg_namespace");
		sql = addPgCatalogPrefix(sql, "pg_attribute");
		sql = addPgCatalogPrefix(sql, "pg_tables");

		// TODO: Remove hack needed to handle CURRENT_SCHEMA function
		// Proper fix: Register CURRENT_SCHEMA as a Calcite ScalarFunction that
		// returns the current schema from session context
		sql = sql.replaceAll("(?i)CURRENT_SCHEMA\\s*\\(\\s*\\)", "'public'");
		sql = sql.replaceAll("(?i)\\bCURRENT_SCHEMA\\b", "'public'");

		// TODO: Remove hack needed to handle CURRENT_DATABASE function
		// Proper fix: Register CURRENT_DATABASE as a Calcite ScalarFunction that
		// returns the database name from session context
		sql = sql.replaceAll("(?i)CURRENT_DATABASE\\s*\\(\\s*\\)", "'" + database + "'");

		// TODO: Remove hack needed to handle CURRENT_USER keyword
		// Proper fix: Register CURRENT_USER as a Calcite ScalarFunction that
		// returns the authenticated username from session context
		sql = sql.replaceAll("(?i)\\bCURRENT_USER\\b", "'convex'");

		// TODO: Remove hack needed to handle SESSION_USER keyword
		// Proper fix: Register SESSION_USER as a Calcite ScalarFunction that
		// returns the session username from session context
		sql = sql.replaceAll("(?i)\\bSESSION_USER\\b", "'convex'");

		// TODO: Remove hack needed to handle version() function
		// Proper fix: Register version() as a Calcite ScalarFunction that
		// returns appropriate version string
		sql = sql.replaceAll("(?i)\\bversion\\s*\\(\\s*\\)", "'PostgreSQL 15.0 (Convex SQL)'");

		// TODO: Remove hack needed to handle pg_backend_pid() function
		// Proper fix: Register pg_backend_pid() as a Calcite ScalarFunction that
		// returns the connection's process ID from session context
		sql = sql.replaceAll("(?i)pg_backend_pid\\s*\\(\\s*\\)", String.valueOf(processId));

		// TODO: Remove hack needed to handle PostgreSQL cast syntax (::type)
		// Proper fix: Use Calcite Babel parser with PostgreSQL conformance,
		// which natively supports :: cast syntax
		sql = sql.replaceAll("::integer", "");
		sql = sql.replaceAll("::int", "");
		sql = sql.replaceAll("::int4", "");
		sql = sql.replaceAll("::int8", "");
		sql = sql.replaceAll("::bigint", "");
		sql = sql.replaceAll("::text", "");
		sql = sql.replaceAll("::varchar", "");
		sql = sql.replaceAll("::regclass", "");
		sql = sql.replaceAll("::oid", "");

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

	/**
	 * Prepared statement info - stores the query and parameter types.
	 */
	private record PreparedStmt(String query, int[] paramTypes) {}

	/**
	 * Portal info - a bound prepared statement ready for execution.
	 */
	private record Portal(PreparedStmt stmt, byte[][] paramValues, short[] paramFormats, short[] resultFormats) {}

	// Statements are named prepared statements (Parse creates these)
	private final Map<String, PreparedStmt> statements = new java.util.HashMap<>();
	// Portals are bound statements ready to execute (Bind creates these)
	private final Map<String, Portal> portals = new java.util.HashMap<>();

	private void handleParse(ChannelHandlerContext ctx, PgMessageDecoder.Parse parse) {
		if (!authenticated) {
			sendErrorAndClose(ctx, "28000", "not authenticated");
			return;
		}

		try {
			String name = parse.name();
			String query = parse.query();
			log.debug("Parse: name='{}', query='{}'", name, query);

			// Close existing statement with same name (PostgreSQL behavior)
			statements.remove(name);

			// Store the prepared statement
			statements.put(name, new PreparedStmt(query, parse.paramTypes()));

			write(ctx, ParseComplete.INSTANCE);
		} catch (Exception e) {
			log.warn("Parse error: {}", e.getMessage(), e);
			write(ctx, ErrorResponse.fromException(e));
		}
	}

	private void handleBind(ChannelHandlerContext ctx, PgMessageDecoder.Bind bind) {
		if (!authenticated) {
			sendErrorAndClose(ctx, "28000", "not authenticated");
			return;
		}

		try {
			String portalName = bind.portal();
			String stmtName = bind.statement();
			log.debug("Bind: portal='{}', statement='{}', params={}", portalName, stmtName, bind.paramValues().length);

			// Find the prepared statement
			PreparedStmt stmt = statements.get(stmtName);
			if (stmt == null) {
				write(ctx, ErrorResponse.builder()
					.severity("ERROR")
					.code("26000") // invalid_sql_statement_name
					.message("prepared statement \"" + stmtName + "\" does not exist")
					.build());
				return;
			}

			// Close existing portal with same name (PostgreSQL behavior)
			portals.remove(portalName);

			// Create the portal with bound parameters
			portals.put(portalName, new Portal(stmt, bind.paramValues(), bind.paramFormats(), bind.resultFormats()));

			write(ctx, BindComplete.INSTANCE);
		} catch (Exception e) {
			log.warn("Bind error: {}", e.getMessage(), e);
			write(ctx, ErrorResponse.fromException(e));
		}
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
				PreparedStmt stmt = statements.get(describe.name());
				if (stmt == null) {
					write(ctx, ErrorResponse.builder()
						.severity("ERROR")
						.code("26000")
						.message("prepared statement \"" + describe.name() + "\" does not exist")
						.build());
					return;
				}

				// Send parameter description
				write(ctx, new ParameterDescription(stmt.paramTypes()));

				String query = rewriteQuery(stmt.query());
				if (query != null && query.toUpperCase().trim().startsWith("SELECT")) {
					// Execute to get metadata (substitute $N with NULL for metadata query)
					String metaQuery = query.replaceAll("\\$\\d+", "NULL");
					try (Statement s = connection.createStatement();
						 ResultSet rs = s.executeQuery(metaQuery)) {
						write(ctx, RowDescription.fromMetaData(rs.getMetaData()));
					} catch (SQLException e) {
						// If metadata query fails, return NoData
						write(ctx, NoData.INSTANCE);
					}
				} else {
					write(ctx, NoData.INSTANCE);
				}
			} else {
				// Describe portal
				Portal portal = portals.get(describe.name());
				if (portal == null) {
					write(ctx, ErrorResponse.builder()
						.severity("ERROR")
						.code("34000") // invalid_cursor_name
						.message("portal \"" + describe.name() + "\" does not exist")
						.build());
					return;
				}

				String query = rewriteQuery(portal.stmt().query());
				if (query != null && query.toUpperCase().trim().startsWith("SELECT")) {
					String metaQuery = query.replaceAll("\\$\\d+", "NULL");
					try (Statement s = connection.createStatement();
						 ResultSet rs = s.executeQuery(metaQuery)) {
						write(ctx, RowDescription.fromMetaData(rs.getMetaData()));
					} catch (SQLException e) {
						write(ctx, NoData.INSTANCE);
					}
				} else {
					write(ctx, NoData.INSTANCE);
				}
			}
		} catch (Exception e) {
			log.warn("Describe error: {}", e.getMessage(), e);
			write(ctx, ErrorResponse.fromException(e));
		}
	}

	private void handleExecute(ChannelHandlerContext ctx, PgMessageDecoder.Execute execute) {
		if (!authenticated) {
			sendErrorAndClose(ctx, "28000", "not authenticated");
			return;
		}

		String portalName = execute.portal();
		log.debug("Execute: portal='{}', maxRows={}", portalName, execute.maxRows());

		try {
			Portal portal = portals.get(portalName);
			if (portal == null) {
				write(ctx, ErrorResponse.builder()
					.severity("ERROR")
					.code("34000")
					.message("portal \"" + portalName + "\" does not exist")
					.build());
				return;
			}

			String query = portal.stmt().query();
			if (query == null || query.trim().isEmpty()) {
				write(ctx, new EmptyQueryResponse());
				return;
			}

			executeWithParameters(ctx, query, portal.paramValues(), portal.paramFormats());
		} catch (SQLException e) {
			log.warn("Execute error: {}", e.getMessage(), e);
			write(ctx, ErrorResponse.fromException(e));
		} catch (RuntimeException e) {
			// Runtime exceptions from Calcite (type coercion, etc.) are query errors
			log.warn("Query execution error: {}", e.getMessage(), e);
			write(ctx, ErrorResponse.fromException(e));
		} catch (Exception e) {
			log.error("Unexpected error during execute", e);
			write(ctx, ErrorResponse.fromException(e));
		}
	}

	/**
	 * Execute a query with bound parameters.
	 */
	private void executeWithParameters(ChannelHandlerContext ctx, String sql, byte[][] paramValues, short[] paramFormats) throws SQLException {
		sql = rewriteQuery(sql);

		if (sql == null) {
			write(ctx, CommandComplete.select(0));
			return;
		}

		// If no parameters, execute directly
		if (paramValues == null || paramValues.length == 0) {
			executeQuery(ctx, sql);
			return;
		}

		// For pg_catalog queries, substitute parameters directly
		// (Calcite's virtual tables don't support prepared statements well)
		String lowerSql = sql.toLowerCase();
		if (lowerSql.contains("pg_catalog") || lowerSql.contains("pg_database") ||
			lowerSql.contains("pg_type") || lowerSql.contains("pg_class") ||
			lowerSql.contains("pg_namespace") || lowerSql.contains("pg_attribute") ||
			lowerSql.contains("pg_tables")) {
			String substituted = substituteParameters(sql, paramValues, paramFormats);
			executeQuery(ctx, substituted);
			return;
		}

		// Convert $1, $2 to ? for JDBC
		String jdbcSql = sql.replaceAll("\\$\\d+", "?");

		try (PreparedStatement pstmt = connection.prepareStatement(jdbcSql)) {
			// Bind parameters
			for (int i = 0; i < paramValues.length; i++) {
				byte[] value = paramValues[i];
				if (value == null) {
					pstmt.setNull(i + 1, java.sql.Types.NULL);
				} else {
					// Determine format: 0 = text, 1 = binary
					short format = (paramFormats != null && paramFormats.length > 0)
						? (paramFormats.length == 1 ? paramFormats[0] : paramFormats[i])
						: 0;

					if (format == 0) {
						// Text format - convert bytes to string
						String strValue = new String(value, java.nio.charset.StandardCharsets.UTF_8);
						pstmt.setString(i + 1, strValue);
					} else {
						// Binary format - set as bytes
						pstmt.setBytes(i + 1, value);
					}
				}
			}

			boolean hasResultSet = pstmt.execute();
			if (hasResultSet) {
				try (ResultSet rs = pstmt.getResultSet()) {
					sendResultSet(ctx, rs);
				}
			} else {
				int updateCount = pstmt.getUpdateCount();
				String upperSql = sql.toUpperCase().trim();
				if (upperSql.startsWith("INSERT")) {
					write(ctx, CommandComplete.insert(updateCount));
				} else if (upperSql.startsWith("UPDATE")) {
					write(ctx, CommandComplete.update(updateCount));
				} else if (upperSql.startsWith("DELETE")) {
					write(ctx, CommandComplete.delete(updateCount));
				} else {
					write(ctx, new CommandComplete("OK"));
				}
			}
		}
	}

	/**
	 * Adds pg_catalog. prefix to a table name if not already qualified.
	 * PostgreSQL's search path includes pg_catalog, but Calcite requires explicit schema.
	 */
	private String addPgCatalogPrefix(String sql, String tableName) {
		// Match table name that's not already prefixed with pg_catalog.
		// Use word boundaries and negative lookbehind for the dot
		String pattern = "(?i)(?<!pg_catalog\\.)\\b" + tableName + "\\b";
		return sql.replaceAll(pattern, "pg_catalog." + tableName);
	}

	/**
	 * Substitutes $1, $2, etc. with actual parameter values.
	 * Used for pg_catalog queries where PreparedStatement doesn't work well.
	 */
	private String substituteParameters(String sql, byte[][] paramValues, short[] paramFormats) {
		String result = sql;
		for (int i = 0; i < paramValues.length; i++) {
			String placeholder = "\\$" + (i + 1);
			String replacement;

			if (paramValues[i] == null) {
				replacement = "NULL";
			} else {
				short format = (paramFormats != null && paramFormats.length > 0)
					? (paramFormats.length == 1 ? paramFormats[0] : paramFormats[i])
					: 0;

				if (format == 0) {
					// Text format
					String strValue = new String(paramValues[i], java.nio.charset.StandardCharsets.UTF_8);
					// Check if it looks like a boolean or number
					if (strValue.equalsIgnoreCase("t") || strValue.equalsIgnoreCase("true")) {
						replacement = "TRUE";
					} else if (strValue.equalsIgnoreCase("f") || strValue.equalsIgnoreCase("false")) {
						replacement = "FALSE";
					} else if (strValue.matches("-?\\d+(\\.\\d+)?")) {
						replacement = strValue; // Number, no quotes
					} else {
						// String - escape single quotes and wrap
						replacement = "'" + strValue.replace("'", "''") + "'";
					}
				} else {
					// Binary format - represent as hex
					StringBuilder hex = new StringBuilder("'\\x");
					for (byte b : paramValues[i]) {
						hex.append(String.format("%02x", b & 0xff));
					}
					hex.append("'");
					replacement = hex.toString();
				}
			}

			result = result.replaceFirst(placeholder, replacement);
		}
		return result;
	}

	private void handleClose(ChannelHandlerContext ctx, PgMessageDecoder.Close close) {
		log.debug("Close: type={}, name='{}'", (char) close.type(), close.name());

		if (close.type() == 'S') {
			statements.remove(close.name());
		} else {
			portals.remove(close.name());
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
