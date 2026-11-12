package convex.db.jdbc;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.BatchUpdateException;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ParameterMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLDataException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.SQLSyntaxErrorException;
import java.sql.Statement;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

import org.apache.calcite.jdbc.CalciteConnection;
import org.apache.calcite.sql.SqlKind;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.SqlWith;
import org.apache.calcite.sql.parser.SqlParser;
import org.apache.calcite.sql.parser.SqlParseException;
import org.apache.calcite.sql.validate.SqlValidatorException;

import convex.db.calcite.ConvexDdlExecutor;
import convex.lattice.cursor.ReadOnlyCursorException;

/** SQL access checks and error translation, including lazy result fetching. */
final class JdbcErrors {
	private JdbcErrors() {}

	static Connection wrap(Connection connection, Runnable writeAttempted) {
		var config = ((CalciteConnection) connection).config();
		SqlParser.Config parser = SqlParser.config().withParserFactory(ConvexDdlExecutor.PARSER_FACTORY)
				.withQuotedCasing(config.quotedCasing()).withUnquotedCasing(config.unquotedCasing())
				.withQuoting(config.quoting()).withConformance(config.conformance())
				.withCaseSensitive(config.caseSensitive());
		return (Connection) wrap(connection, CalciteConnection.class, null, null, parser, null, writeAttempted);
	}

	private static Object wrap(Object target, Class<?> type, Connection connection, Statement statement,
			SqlParser.Config parser, Boolean query, Runnable writeAttempted) {
		return Proxy.newProxyInstance(JdbcErrors.class.getClassLoader(), new Class<?>[] {type},
				new Boundary(target, connection, statement, parser, query, writeAttempted));
	}

	private static final class Boundary implements java.lang.reflect.InvocationHandler {
		private final Object target;
		private final Connection connection;
		private final Statement statement;
		private ResultSet lastResult;
		private ResultSet lastWrappedResult;
		private final SqlParser.Config parser;
		private final Boolean preparedQuery;
		private boolean batchWrites;
		private final Runnable writeAttempted;

		Boundary(Object target, Connection connection, Statement statement, SqlParser.Config parser, Boolean query,
				Runnable writeAttempted) {
			this.target = target;
			this.connection = connection;
			this.statement = statement;
			this.parser = parser;
			this.preparedQuery = query;
			this.writeAttempted = writeAttempted;
		}

		@Override
		public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
			String name = method.getName();
			if (name.equals("equals")) return proxy == args[0];
			if (name.equals("hashCode")) return System.identityHashCode(proxy);
			if (name.equals("unwrap")) {
				if (((Class<?>) args[0]).isInstance(proxy)) return proxy;
				throw new SQLException("Cannot unwrap JDBC access boundary to " + args[0], "0A000");
			}
			if (name.equals("isWrapperFor")) return ((Class<?>) args[0]).isInstance(proxy);
			if (name.equals("getConnection") && connection != null) return connection;
			if (name.equals("getStatement") && statement != null) return statement;
			if (target instanceof DatabaseMetaData && name.equals("isReadOnly")) return connection.isReadOnly();
			Connection owner = proxy instanceof Connection c ? c : connection;
			try {
				Boolean query = preparedQuery;
				boolean prepare = name.equals("prepareStatement");
				boolean batch = name.equals("executeBatch") || name.equals("executeLargeBatch");
				boolean execute = name.startsWith("execute");
				boolean add = name.equals("addBatch");
				if ((prepare || execute || add) && !batch) {
					if (args != null && args.length > 0 && args[0] instanceof String sql) {
						query = isQuery(SqlParser.create(sql, parser).parseStmt());
					}
					if (Boolean.FALSE.equals(query)) {
						if (owner.isReadOnly()) throw new SQLException("Cannot execute a write on a read-only database connection", "25006");
						if (name.equals("executeQuery")) throw new SQLException("executeQuery requires a query", "07000");
					}
				}
				if (batch && batchWrites && owner.isReadOnly()) {
					throw new BatchUpdateException("Cannot execute writes on a read-only database connection", "25006", new int[0]);
				}
				// DDL may run during preparation, and a failing write may already have effects.
				if ((prepare || execute) && (batch ? batchWrites : Boolean.FALSE.equals(query))) writeAttempted.run();
				Object value = method.invoke(target, args);
				if (add && Boolean.FALSE.equals(query)) batchWrites = true;
				if (batch || name.equals("clearBatch")) batchWrites = false;
				Class<?> resultType = method.getReturnType();
				// AvaticaPreparedStatement also implements ParameterMetaData and returns
				// itself for that API: wrap by the declared return type, not incidental interfaces.
				if (resultType == PreparedStatement.class) return wrap(value, PreparedStatement.class, owner, null, parser, query, writeAttempted);
				if (resultType == Statement.class) return wrap(value, Statement.class, owner, null, parser, null, writeAttempted);
				if (resultType == DatabaseMetaData.class) return wrap(value, DatabaseMetaData.class, owner, null, parser, null, writeAttempted);
				if (resultType == ParameterMetaData.class) return wrap(value, ParameterMetaData.class, owner, null, parser, null, writeAttempted);
				if (resultType == ResultSet.class && value instanceof ResultSet r) {
					if (r != lastResult) {
						lastResult = r;
						lastWrappedResult = (ResultSet) wrap(r, ResultSet.class, owner,
								proxy instanceof Statement s ? s : statement, parser, null, writeAttempted);
					}
					return lastWrappedResult;
				}
				return value;
			} catch (SqlParseException e) {
				throw translate(e);
			} catch (InvocationTargetException e) {
				Throwable failure = e.getCause();
				// Do not turn VM failures or assertion failures into ordinary SQL errors.
				if (failure instanceof Error && !(failure instanceof ExceptionInInitializerError)) throw failure;
				for (Class<?> exception : method.getExceptionTypes()) {
					if (exception == SQLException.class) throw translate(failure);
				}
				throw failure;
			}
		}
	}

	private static boolean isQuery(SqlNode node) {
		if (node instanceof SqlWith with) return isQuery(with.body);
		return node.isA(SqlKind.QUERY) || node.getKind() == SqlKind.EXPLAIN;
	}

	static SQLException translate(Throwable failure) {
		Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
		String state = null;
		for (Throwable cause = failure; cause != null && seen.add(cause); cause = cause.getCause()) {
			if (cause instanceof SQLException sql && sql.getSQLState() != null) return preserveBatch(failure, sql);
			if (cause instanceof SqlParseException) state = "42601";
			else if (cause instanceof ReadOnlyCursorException) state = "25006";
			else if (cause instanceof SqlValidatorException && state == null) state = "42000";
			else if (cause instanceof NumberFormatException) state = "22P02";
			else if (cause instanceof ArithmeticException) {
				// Java's arithmetic exception has no structured reason. These are the
				// JVM/BigDecimal zero-divisor messages; other arithmetic failures are range errors.
				String message = cause.getMessage();
				state = "/ by zero".equals(message) || "Division by zero".equals(message)
						|| "BigInteger divide by zero".equals(message) ? "22012" : "22003";
			} else if (cause instanceof SQLFeatureNotSupportedException) state = "0A000";
		}
		if (state == null && failure instanceof SQLException sql) return sql;
		String message = failure.getMessage();
		SQLException translated;
		if (state != null && state.startsWith("42")) translated = new SQLSyntaxErrorException(message, state, failure);
		else if (state != null && state.startsWith("22")) translated = new SQLDataException(message, state, failure);
		else if ("0A000".equals(state)) translated = new SQLFeatureNotSupportedException(message, state, failure);
		else translated = new SQLException(message, state == null ? "HY000" : state, failure);
		return preserveBatch(failure, translated);
	}

	private static SQLException preserveBatch(Throwable failure, SQLException translated) {
		if (failure instanceof BatchUpdateException batch && failure != translated) {
			var result = new BatchUpdateException(batch.getMessage(), translated.getSQLState(),
					batch.getErrorCode(), batch.getLargeUpdateCounts(), batch);
			if (batch.getNextException() != null) result.setNextException(batch.getNextException());
			return result;
		}
		return translated;
	}
}
