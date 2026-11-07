package convex.db.jdbc;

import java.sql.SQLDataException;
import java.sql.SQLException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/** SQLSTATE translation at the boundary with Calcite's generated Java code. */
public final class SQLExceptions {

	private SQLExceptions() {}

	/** Carries a data exception through expression/enumerator APIs that cannot throw checked exceptions. */
	public static RuntimeException data(String message, String state, Throwable cause) {
		return new IllegalArgumentException(message, new SQLDataException(message, state, cause));
	}

	/**
	 * Preserves an explicit SQLSTATE, including one nested in a Calcite wrapper, before
	 * translating Java data exceptions. Unrecognised runtime failures and VM errors are
	 * rethrown, rather than being misreported as bad input.
	 */
	public static SQLException translate(Throwable failure) {
		Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
		for (Throwable t = failure; t != null && seen.add(t); t = t.getCause()) {
			if (t instanceof SQLException sql && sql.getSQLState() != null) {
				if (failure == sql) return sql;
				return new SQLException(failure.getMessage(), sql.getSQLState(), sql.getErrorCode(), failure);
			}
		}
		seen.clear();
		for (Throwable t = failure; t != null && seen.add(t); t = t.getCause()) {
			if (t instanceof NumberFormatException) {
				return new SQLDataException(t.getMessage(), "22P02", failure);
			}
			if (t instanceof ArithmeticException) {
				// Generated Java has no typed distinction between division by zero and
				// overflow. Recognise only the JDK arithmetic diagnostics here; SQL and
				// pgwire messages must never be searched for error keywords.
				String state = switch (String.valueOf(t.getMessage())) {
					case "/ by zero", "BigInteger divide by zero", "Division by zero" -> "22012";
					default -> "22003";
				};
				return new SQLDataException(t.getMessage(), state, failure);
			}
		}
		if (failure instanceof SQLException sql) return sql;
		if (failure instanceof RuntimeException runtime) throw runtime;
		if (failure instanceof Error error) throw error;
		return new SQLException(failure.getMessage(), failure);
	}
}
