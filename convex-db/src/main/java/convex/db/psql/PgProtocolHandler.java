package convex.db.psql;

import convex.core.crypto.TLS;
import convex.db.calcite.StalePlanException;
import convex.db.psql.msg.*;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.ssl.SslHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import javax.net.ssl.SSLContext;

/**
 * Handles the PostgreSQL wire protocol and executes SQL queries.
 */
public class PgProtocolHandler extends ChannelInboundHandlerAdapter {

	private static final Logger log = LoggerFactory.getLogger(PgProtocolHandler.class);
	private static final AtomicInteger processIdCounter = new AtomicInteger(1000);

	/** Single-quoted literals and double-quoted identifiers, with doubled-quote escapes */
	private static final Pattern QUOTED = Pattern.compile("'(?:[^']|'')*'|\"(?:[^\"]|\"\")*\"");

	/** The ::type casts removed by rewriteQuery, matched as whole type names */
	private static final Pattern CAST = Pattern.compile(
		"(?i)::(?:integer|int4|int8|int|bigint|text|varchar|regclass|oid)\\b");

	/** Time allowed for a client's TLS handshake after the server answers SSLRequest */
	private static final long TLS_HANDSHAKE_TIMEOUT_MILLIS = 10_000;

	private final Supplier<Connection> connectionSupplier;
	private final String requiredPassword;
	private final SSLContext sslContext;
	private final boolean allowPlaintext;

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
		this(connectionSupplier, requiredPassword, null, true);
	}

	/**
	 * Creates a handler that may offer TLS. The channel's decoder must be created with
	 * TLS offered when a context is given.
	 *
	 * @param connectionSupplier Supplies JDBC connections for query execution
	 * @param requiredPassword Password required for authentication, or null for trust auth
	 * @param sslContext Context presenting the server's TLS identity, or null not to offer TLS
	 * @param allowPlaintext True to admit clients that do not request TLS when it is offered
	 */
	public PgProtocolHandler(Supplier<Connection> connectionSupplier, String requiredPassword,
			SSLContext sslContext, boolean allowPlaintext) {
		this.connectionSupplier = connectionSupplier;
		this.requiredPassword = requiredPassword;
		this.sslContext = sslContext;
		this.allowPlaintext = allowPlaintext;
		this.processId = processIdCounter.incrementAndGet();
		this.secretKey = ThreadLocalRandom.current().nextInt();
	}

	/**
	 * Set by an error in the extended query protocol. Like PostgreSQL, the handler then
	 * discards every message until Sync, so no later message of a failed batch executes.
	 */
	private boolean discardUntilSync = false;

	/**
	 * The transaction status every ReadyForQuery reports: idle, in a transaction block, or
	 * in a block an error has failed, where statements are refused until the block ends.
	 */
	private byte transactionStatus = ReadyForQuery.IDLE;

	@Override
	public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
		if (discardUntilSync && isDiscardable(msg)) {
			log.debug("Discarding {} until Sync after an error", msg.getClass().getSimpleName());
			return;
		}
		if (msg instanceof PgMessageDecoder.SSLRequest) {
			handleSSLRequest(ctx);
		} else if (msg instanceof PgMessageDecoder.GSSENCRequest) {
			// GSSAPI encryption is not offered; the client continues with SSLRequest or startup
			writeByte(ctx, 'N');
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
		if (sslContext == null || isTLS(ctx)) {
			// TLS not offered, or already started
			writeByte(ctx, 'N');
			return;
		}
		// 'S' leaves in plaintext; everything the client sends next passes through TLS first
		writeByte(ctx, 'S');
		SslHandler tls = new SslHandler(TLS.serverEngine(sslContext));
		tls.setHandshakeTimeoutMillis(TLS_HANDSHAKE_TIMEOUT_MILLIS);
		ctx.pipeline().addFirst(tls);
	}

	private static boolean isTLS(ChannelHandlerContext ctx) {
		return ctx.pipeline().get(SslHandler.class) != null;
	}

	private static void writeByte(ChannelHandlerContext ctx, char response) {
		ByteBuf buf = ctx.alloc().buffer(1);
		buf.writeByte(response);
		ctx.writeAndFlush(buf);
	}

	private void handleStartup(ChannelHandlerContext ctx, PgMessageDecoder.StartupMessage startup) {
		log.debug("Startup: version={}.{}, params={}", startup.majorVersion(), startup.minorVersion(), startup.params());

		if (sslContext != null && !allowPlaintext && !isTLS(ctx)) {
			// As PostgreSQL's hostssl: a client that did not request TLS is refused
			write(ctx, ErrorResponse.builder()
				.severity("FATAL")
				.code("28000")
				.message("connection requires TLS")
				.build());
			ctx.flush();
			ctx.close();
			return;
		}

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
			write(ctx, readyForQuery());
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

			try {
				if (transactionControl(ctx, sql)) continue;
				requireTransactionNotFailed();

				// Handle special commands
				if (sql.toUpperCase().startsWith("SET ")) {
					// Ignore SET commands for now
					write(ctx, CommandComplete.set());
					continue;
				}

				executeQuery(ctx, sql);
			} catch (SQLException e) {
				log.warn("Query error: {}", e.getMessage(), e);
				writeError(ctx, ErrorResponse.fromException(e));
				// Stop processing on error
				break;
			} catch (RuntimeException e) {
				// Runtime exceptions from Calcite (type coercion, etc.) are query errors
				log.warn("Query execution error: {}", e.getMessage(), e);
				writeError(ctx, ErrorResponse.fromException(e));
				break;
			} catch (Exception e) {
				log.error("Unexpected error", e);
				writeError(ctx, ErrorResponse.fromException(e));
				break;
			}
		}

		write(ctx, readyForQuery());
		ctx.flush();
	}

	// ========== Transaction control ==========

	/**
	 * Runs a statement if it is transaction control, on the connection's own transaction:
	 * BEGIN turns autocommit off, which forks the database, COMMIT syncs the fork and ROLLBACK
	 * discards it. COMMIT of a failed block rolls it back, as PostgreSQL does. BEGIN inside a
	 * block and COMMIT or ROLLBACK outside one change nothing.
	 *
	 * @return true if the statement was transaction control and has completed
	 * @throws SQLException with SQLSTATE 0A000 for transaction control the server does not support
	 */
	private boolean transactionControl(ChannelHandlerContext ctx, String sql) throws SQLException {
		PgTransactionControl.Kind kind = PgTransactionControl.classify(sql);
		if (kind == null) return false;
		switch (kind) {
			case BEGIN -> {
				if (transactionStatus == ReadyForQuery.IDLE) {
					connection.setAutoCommit(false);
					transactionStatus = ReadyForQuery.IN_TRANSACTION;
				}
				write(ctx, CommandComplete.begin());
			}
			case COMMIT -> {
				boolean failed = (transactionStatus == ReadyForQuery.FAILED_TRANSACTION);
				endTransaction(!failed);
				write(ctx, failed ? CommandComplete.rollback() : CommandComplete.commit());
			}
			case ROLLBACK -> {
				endTransaction(false);
				write(ctx, CommandComplete.rollback());
			}
		}
		return true;
	}

	/** Ends a transaction block, if one is open, and returns the connection to autocommit. */
	private void endTransaction(boolean commit) throws SQLException {
		if (transactionStatus == ReadyForQuery.IDLE) return;
		transactionStatus = ReadyForQuery.IDLE;
		try {
			if (commit) connection.commit();
			else connection.rollback();
		} finally {
			connection.setAutoCommit(true);
		}
	}

	/** Refuses a statement in a failed transaction block, as PostgreSQL does, until the block ends. */
	private void requireTransactionNotFailed() throws SQLException {
		if (transactionStatus == ReadyForQuery.FAILED_TRANSACTION) {
			throw new SQLException("current transaction is aborted, commands ignored until end of transaction block", "25P02");
		}
	}

	/** Reports an error; inside a transaction block it fails the block. */
	private void writeError(ChannelHandlerContext ctx, ErrorResponse error) {
		write(ctx, error);
		if (transactionStatus == ReadyForQuery.IN_TRANSACTION) transactionStatus = ReadyForQuery.FAILED_TRANSACTION;
	}

	private ReadyForQuery readyForQuery() {
		return (transactionStatus == ReadyForQuery.IDLE) ? ReadyForQuery.IDLE_INSTANCE : new ReadyForQuery(transactionStatus);
	}

	private void executeQuery(ChannelHandlerContext ctx, String sql) throws SQLException {
		executeQuery(ctx, sql, true);
	}

	/**
	 * @param describeRows Whether a result set is preceded by its RowDescription. True for
	 *        the simple query protocol. False for Execute in the extended protocol, which
	 *        never sends one: there the client asks for it with Describe.
	 */
	private void executeQuery(ChannelHandlerContext ctx, String sql, boolean describeRows) throws SQLException {
		executeQuery(ctx, sql, describeRows, null);
	}

	/**
	 * As {@link #executeQuery(ChannelHandlerContext, String, boolean)}, sending the rows in
	 * the formats the client asked for in Bind, or in text for null.
	 */
	private void executeQuery(ChannelHandlerContext ctx, String sql, boolean describeRows, short[] resultFormats) throws SQLException {
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
					sendResultSet(ctx, rs, describeRows, resultFormats);
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
		if (hasRegexOperator(sql)) {
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
		sql = stripCasts(sql);

		return sql;
	}

	/**
	 * Tests whether SQL uses a PostgreSQL regex operator (~, ~*, !~, !~*).
	 * Quoted literals and identifiers are ignored, so a '~' inside a string
	 * value does not count as an operator.
	 */
	static boolean hasRegexOperator(String sql) {
		String code = QUOTED.matcher(sql).replaceAll("''");
		return code.contains("!~") || code.contains("~*") ||
			(code.contains("~") && !code.contains("~=") && !code.contains("~~"));
	}

	/**
	 * Strips the supported PostgreSQL ::type casts. Matches whole type names
	 * only, so ::int does not consume the prefix of ::int4 or ::interval.
	 */
	static String stripCasts(String sql) {
		return CAST.matcher(sql).replaceAll("");
	}

	/**
	 * Sends the rows of a result, each value in the format the client asked for in Bind:
	 * text, or binary encoded as the type the RowDescription announces for the column.
	 *
	 * @param describeRows Whether to send the RowDescription first
	 * @param resultFormats Bind's result format codes, or null for text
	 */
	private void sendResultSet(ChannelHandlerContext ctx, ResultSet rs, boolean describeRows, short[] resultFormats) throws SQLException {
		ResultSetMetaData meta = rs.getMetaData();

		// Send row description
		if (describeRows) {
			write(ctx, RowDescription.fromMetaData(meta));
		}

		// Send data rows
		long rowCount = 0;
		while (rs.next()) {
			write(ctx, DataRow.fromResultSet(rs, meta, resultFormats));
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
	// The JDBC statement prepared for each statement above, made on its first Execute and
	// reused for every later one: binding new values and executing again skips parsing and
	// planning. By identity: a statement parsed again under the same name is a new one.
	private final Map<PreparedStmt, PreparedStatement> jdbcStatements = new java.util.IdentityHashMap<>();

	private void handleParse(ChannelHandlerContext ctx, PgMessageDecoder.Parse parse) {
		if (!authenticated) {
			sendErrorAndClose(ctx, "28000", "not authenticated");
			return;
		}

		try {
			String name = parse.name();
			String query = parse.query();
			log.debug("Parse: name='{}', query='{}'", name, query);

			// Store the prepared statement, closing an existing one with the same name (PostgreSQL behavior)
			closeJdbcStatement(statements.put(name, new PreparedStmt(query, parse.paramTypes())));

			write(ctx, ParseComplete.INSTANCE);
		} catch (Exception e) {
			log.warn("Parse error: {}", e.getMessage(), e);
			writeExtendedError(ctx, ErrorResponse.fromException(e));
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
				writeExtendedError(ctx, ErrorResponse.builder()
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
			writeExtendedError(ctx, ErrorResponse.fromException(e));
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
					writeExtendedError(ctx, ErrorResponse.builder()
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
					writeExtendedError(ctx, ErrorResponse.builder()
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
			writeExtendedError(ctx, ErrorResponse.fromException(e));
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
				writeExtendedError(ctx, ErrorResponse.builder()
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

			executeWithParameters(ctx, portal.stmt(), portal.paramValues(), portal.paramFormats(), portal.resultFormats());
		} catch (ResultTypeChanged e) {
			log.debug("Execute error: {}", e.getMessage());
			// PostgreSQL's routine for this error, by which clients such as pgjdbc know to
			// prepare the statement again
			writeExtendedError(ctx, ErrorResponse.builder()
				.severity("ERROR")
				.code(e.getSQLState())
				.message(e.getMessage())
				.routine("RevalidateCachedQuery")
				.build());
		} catch (SQLException e) {
			log.warn("Execute error: {}", e.getMessage(), e);
			writeExtendedError(ctx, ErrorResponse.fromException(e));
		} catch (RuntimeException e) {
			// Runtime exceptions from Calcite (type coercion, etc.) are query errors
			log.warn("Query execution error: {}", e.getMessage(), e);
			writeExtendedError(ctx, ErrorResponse.fromException(e));
		} catch (Exception e) {
			log.error("Unexpected error during execute", e);
			writeExtendedError(ctx, ErrorResponse.fromException(e));
		}
	}

	/**
	 * Execute a prepared statement with bound parameters.
	 */
	private void executeWithParameters(ChannelHandlerContext ctx, PreparedStmt stmt, byte[][] paramValues,
			short[] paramFormats, short[] resultFormats) throws SQLException {
		// pgjdbc sends COMMIT and ROLLBACK through the extended protocol
		if (transactionControl(ctx, stmt.query().trim())) return;
		requireTransactionNotFailed();

		String sql = rewriteQuery(stmt.query());
		int[] paramTypes = stmt.paramTypes();

		if (sql == null) {
			write(ctx, CommandComplete.select(0));
			return;
		}

		// If no parameters, execute directly
		if (paramValues == null || paramValues.length == 0) {
			executeQuery(ctx, sql, false, resultFormats);
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
			executeQuery(ctx, substituted, false, resultFormats);
			return;
		}

		// Convert $1, $2 to ? for JDBC
		String jdbcSql = sql.replaceAll("\\$\\d+", "?");

		PreparedStatement pstmt = jdbcStatements.get(stmt);
		boolean kept = (pstmt != null);
		if (!kept) {
			pstmt = connection.prepareStatement(jdbcSql);
			jdbcStatements.put(stmt, pstmt);
		}
		boolean hasResultSet;
		try {
			hasResultSet = bindAndExecute(pstmt, paramValues, paramFormats, paramTypes);
		} catch (SQLException | RuntimeException e) {
			// A table the kept statement uses has changed since it was prepared
			if (!kept || !isStalePlan(e)) throw e;
			pstmt = prepareAgain(stmt, pstmt, jdbcSql);
			hasResultSet = bindAndExecute(pstmt, paramValues, paramFormats, paramTypes);
		}
		if (hasResultSet) {
			try (ResultSet rs = pstmt.getResultSet()) {
				sendResultSet(ctx, rs, false, resultFormats);
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

	/** Binds the parameters of one execution to a statement, replacing those of the last, and executes it. */
	private static boolean bindAndExecute(PreparedStatement pstmt, byte[][] paramValues, short[] paramFormats, int[] paramTypes) throws SQLException {
		pstmt.clearParameters();
		ParameterMetaData inferred = null;
		for (int i = 0; i < paramValues.length; i++) {
			byte[] value = paramValues[i];
			if (value == null) {
				pstmt.setNull(i + 1, java.sql.Types.NULL);
			} else {
				int oid = (paramTypes != null && i < paramTypes.length) ? paramTypes[i] : 0;

				if (!PgMessageDecoder.Bind.binary(paramFormats, i)) {
					// Text format - parse by the declared type, or the type the query implies
					if (oid == 0 || oid == PgType.UNKNOWN) {
						if (inferred == null) inferred = pstmt.getParameterMetaData();
						oid = inferredType(inferred, i + 1);
					}
					String strValue = new String(value, java.nio.charset.StandardCharsets.UTF_8);
					bindTextParameter(pstmt, i + 1, strValue, oid);
				} else {
					// Binary format - decode by the type the client declared in Parse
					bindBinaryParameter(pstmt, i + 1, value, oid);
				}
			}
		}
		return pstmt.execute();
	}

	/**
	 * Prepares a kept statement again after a table it uses changed (CAD039). Like PostgreSQL,
	 * the new JDBC statement replaces the old one only if it returns rows of the same
	 * description, since the client may decode rows by the old one. Otherwise the old one
	 * stays, so every execution fails with 0A000 until the client parses the statement again.
	 */
	private PreparedStatement prepareAgain(PreparedStmt stmt, PreparedStatement old, String jdbcSql) throws SQLException {
		PreparedStatement fresh = connection.prepareStatement(jdbcSql);
		if (!java.util.Objects.equals(describe(old), describe(fresh))) {
			fresh.close();
			throw new ResultTypeChanged();
		}
		jdbcStatements.put(stmt, fresh);
		old.close();
		return fresh;
	}

	/** The RowDescription of a statement's rows, or null if it returns none. */
	private static RowDescription describe(PreparedStatement pstmt) throws SQLException {
		ResultSetMetaData meta = pstmt.getMetaData();
		return (meta == null) ? null : RowDescription.fromMetaData(meta);
	}

	private static boolean isStalePlan(Throwable e) {
		for (Throwable c = e; c != null; c = (c.getCause() == c) ? null : c.getCause()) {
			if (c instanceof StalePlanException) return true;
		}
		return false;
	}

	/** A kept statement, prepared again after its table changed, returns rows of another description. */
	private static final class ResultTypeChanged extends SQLException {
		private static final long serialVersionUID = 1L;

		ResultTypeChanged() {
			super("cached plan must not change result type", "0A000");
		}
	}

	/**
	 * Binds a parameter that arrived in binary format, decoding it by the type OID the
	 * client declared for it in the Parse message.
	 *
	 * <p>The PostgreSQL JDBC driver sends numbers in binary format: setLong arrives as
	 * the 8 bytes of an int8, not as the digits of the number. Binding those bytes as
	 * they are hands Calcite a byte string where it expects a number, and the statement
	 * fails. Types without a binary form in {@link PgBinaryCodec}, and parameters the
	 * client declared no type for, are bound as bytes as before.
	 */
	static void bindBinaryParameter(PreparedStatement pstmt, int index, byte[] value, int oid) throws SQLException {
		Object decoded = PgBinaryCodec.decode(value, oid);
		if (decoded instanceof Long l) pstmt.setLong(index, l);
		else if (decoded instanceof Double d) pstmt.setDouble(index, d);
		else if (decoded instanceof Boolean b) pstmt.setBoolean(index, b);
		else if (decoded instanceof java.math.BigDecimal n) pstmt.setBigDecimal(index, n);
		else if (decoded instanceof String s) pstmt.setString(index, s);
		else if (decoded instanceof Timestamp t) pstmt.setTimestamp(index, t);
		else if (decoded instanceof byte[] b) pstmt.setBytes(index, b);
		else pstmt.setBytes(index, value);
	}


	/**
	 * Binds a parameter that arrived in text format, parsing it as the given type the way
	 * PostgreSQL's input functions do: invalid input is 22P02 and out-of-range input 22003.
	 * Text that compares as a string is bound as one, and so are types without a parser
	 * here, as for binary format.
	 */
	static void bindTextParameter(PreparedStatement pstmt, int index, String value, int oid) throws SQLException {
		switch (oid) {
			case PgType.INT2 -> pstmt.setLong(index, parseInteger(value, Short.MIN_VALUE, Short.MAX_VALUE, "smallint"));
			case PgType.INT4 -> pstmt.setLong(index, parseInteger(value, Integer.MIN_VALUE, Integer.MAX_VALUE, "integer"));
			case PgType.INT8 -> pstmt.setLong(index, parseInteger(value, Long.MIN_VALUE, Long.MAX_VALUE, "bigint"));
			case PgType.OID -> pstmt.setLong(index, parseInteger(value, 0L, 0xffffffffL, "oid"));
			case PgType.FLOAT4 -> pstmt.setDouble(index, parseFloat(value, true));
			case PgType.FLOAT8 -> pstmt.setDouble(index, parseFloat(value, false));
			case PgType.NUMERIC -> pstmt.setBigDecimal(index, parseNumeric(value));
			case PgType.BOOL -> pstmt.setBoolean(index, parseBoolean(value));
			default -> pstmt.setString(index, value);
		}
	}

	/** The type the query implies for an undeclared parameter, as PostgreSQL infers it. */
	private static int inferredType(ParameterMetaData metaData, int index) throws SQLException {
		return switch (metaData.getParameterType(index)) {
			case java.sql.Types.TINYINT, java.sql.Types.SMALLINT -> PgType.INT2;
			case java.sql.Types.INTEGER -> PgType.INT4;
			case java.sql.Types.BIGINT -> PgType.INT8;
			case java.sql.Types.REAL -> PgType.FLOAT4;
			case java.sql.Types.FLOAT, java.sql.Types.DOUBLE -> PgType.FLOAT8;
			case java.sql.Types.DECIMAL, java.sql.Types.NUMERIC -> PgType.NUMERIC;
			case java.sql.Types.BOOLEAN -> PgType.BOOL;
			default -> PgType.UNKNOWN;
		};
	}

	private static final Pattern INTEGER_TEXT = Pattern.compile("[+-]?\\d+");
	private static final Pattern FLOAT_TEXT = Pattern.compile("[+-]?(\\d+(\\.\\d*)?|\\.\\d+)([eE][+-]?\\d+)?");

	private static long parseInteger(String text, long min, long max, String type) throws SQLException {
		String s = text.strip();
		if (!INTEGER_TEXT.matcher(s).matches()) throw invalidInput(type, text);
		java.math.BigInteger v = new java.math.BigInteger(s);
		if (v.bitLength() > 63 || v.longValue() < min || v.longValue() > max) {
			throw new SQLException("value \"" + text + "\" is out of range for type " + type, "22003");
		}
		return v.longValue();
	}

	private static double parseFloat(String text, boolean single) throws SQLException {
		String type = single ? "real" : "double precision";
		String s = text.strip();
		switch (s.toLowerCase(java.util.Locale.ROOT)) {
			case "nan": return Double.NaN;
			case "infinity", "+infinity", "inf", "+inf": return Double.POSITIVE_INFINITY;
			case "-infinity", "-inf": return Double.NEGATIVE_INFINITY;
			default: break;
		}
		if (!FLOAT_TEXT.matcher(s).matches()) throw invalidInput(type, text);
		double v = single ? Float.parseFloat(s) : Double.parseDouble(s);
		// Overflow to infinity, or a non-zero value that underflows to zero
		boolean nonZero = s.replaceFirst("[eE].*", "").matches(".*[1-9].*");
		if (Double.isInfinite(v) || (v == 0.0 && nonZero)) {
			throw new SQLException("\"" + text + "\" is out of range for type " + type, "22003");
		}
		return v;
	}

	/** A decimal number, with an optional exponent. NaN and infinities have no exact value, so are invalid. */
	private static java.math.BigDecimal parseNumeric(String text) throws SQLException {
		String s = text.strip();
		if (!FLOAT_TEXT.matcher(s).matches()) throw invalidInput("numeric", text);
		try {
			return new java.math.BigDecimal(s);
		} catch (NumberFormatException e) {
			// The exponent is beyond the range of a BigDecimal scale
			throw new SQLException("value \"" + text + "\" is out of range for type numeric", "22003");
		}
	}

	/** PostgreSQL's boolean spellings: true, yes, on, 1 and their opposites, or unique prefixes. */
	private static boolean parseBoolean(String text) throws SQLException {
		String s = text.strip().toLowerCase(java.util.Locale.ROOT);
		if (!s.isEmpty()) {
			if ("true".startsWith(s) || "yes".startsWith(s) || s.equals("1") || (s.length() >= 2 && "on".startsWith(s))) return true;
			if ("false".startsWith(s) || "no".startsWith(s) || s.equals("0") || (s.length() >= 2 && "off".startsWith(s))) return false;
		}
		throw invalidInput("boolean", text);
	}

	private static SQLException invalidInput(String type, String text) {
		return new SQLException("invalid input syntax for type " + type + ": \"" + text + "\"", "22P02");
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
				if (!PgMessageDecoder.Bind.binary(paramFormats, i)) {
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
			closeJdbcStatement(statements.remove(close.name()));
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
		discardUntilSync = false;
		write(ctx, readyForQuery());
		ctx.flush();
	}

	/** Reports an error from an extended query message, discarding messages until Sync. */
	private void writeExtendedError(ChannelHandlerContext ctx, ErrorResponse error) {
		writeError(ctx, error);
		discardUntilSync = true;
	}

	private static boolean isDiscardable(Object msg) {
		return msg instanceof PgMessageDecoder.Parse || msg instanceof PgMessageDecoder.Bind
			|| msg instanceof PgMessageDecoder.Describe || msg instanceof PgMessageDecoder.Execute
			|| msg instanceof PgMessageDecoder.Close || msg instanceof PgMessageDecoder.Flush
			|| msg instanceof PgMessageDecoder.Query;
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

	/** Closes the JDBC statement prepared for a statement that is closed or replaced, if there is one. */
	private void closeJdbcStatement(PreparedStmt stmt) {
		PreparedStatement pstmt = (stmt == null) ? null : jdbcStatements.remove(stmt);
		if (pstmt != null) {
			try {
				pstmt.close();
			} catch (SQLException e) {
				log.warn("Error closing prepared statement", e);
			}
		}
	}

	private void closeConnection() {
		for (PreparedStmt stmt : new java.util.ArrayList<>(jdbcStatements.keySet())) {
			closeJdbcStatement(stmt);
		}
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
