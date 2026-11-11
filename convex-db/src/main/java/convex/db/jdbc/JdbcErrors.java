package convex.db.jdbc;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.BatchUpdateException;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
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
import org.apache.calcite.sql.parser.SqlParseException;
import org.apache.calcite.sql.validate.SqlValidatorException;

/** The JDBC boundary, including lazy result fetching, for Calcite's unchecked query failures. */
final class JdbcErrors {
	private JdbcErrors() {}

	static Connection wrap(Connection connection) {
		return (Connection) wrap(connection, CalciteConnection.class, null, null);
	}

	private static Object wrap(Object target, Class<?> type, Connection connection, Statement statement) {
		return Proxy.newProxyInstance(JdbcErrors.class.getClassLoader(), new Class<?>[] {type},
				new Boundary(target, connection, statement));
	}

	private static final class Boundary implements java.lang.reflect.InvocationHandler {
		private final Object target;
		private final Connection connection;
		private final Statement statement;
		private ResultSet lastResult;
		private ResultSet lastWrappedResult;

		Boundary(Object target, Connection connection, Statement statement) {
			this.target = target;
			this.connection = connection;
			this.statement = statement;
		}

		@Override
		public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
			String name = method.getName();
			if (name.equals("equals")) return proxy == args[0];
			if (name.equals("hashCode")) return System.identityHashCode(proxy);
			if (name.equals("unwrap") && ((Class<?>) args[0]).isInstance(proxy)) return proxy;
			if (name.equals("isWrapperFor") && ((Class<?>) args[0]).isInstance(proxy)) return true;
			if (name.equals("getConnection") && connection != null) return connection;
			if (name.equals("getStatement") && statement != null) return statement;
			try {
				Object value = method.invoke(target, args);
				Connection owner = proxy instanceof Connection c ? c : connection;
				Class<?> resultType = method.getReturnType();
				// AvaticaPreparedStatement also implements ParameterMetaData and returns
				// itself for that API: wrap by the declared return type, not incidental interfaces.
				if (resultType == PreparedStatement.class) return wrap(value, PreparedStatement.class, owner, null);
				if (resultType == Statement.class) return wrap(value, Statement.class, owner, null);
				if (resultType == DatabaseMetaData.class) return wrap(value, DatabaseMetaData.class, owner, null);
				if (resultType == ResultSet.class && value instanceof ResultSet r) {
					if (r != lastResult) {
						lastResult = r;
						lastWrappedResult = (ResultSet) wrap(r, ResultSet.class, owner,
								proxy instanceof Statement s ? s : statement);
					}
					return lastWrappedResult;
				}
				return value;
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

	static SQLException translate(Throwable failure) {
		Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
		String state = null;
		for (Throwable cause = failure; cause != null && seen.add(cause); cause = cause.getCause()) {
			if (cause instanceof SQLException sql && sql.getSQLState() != null) return preserveBatch(failure, sql);
			if (cause instanceof SqlParseException) state = "42601";
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
		else translated = new SQLException(message, "HY000", failure);
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
