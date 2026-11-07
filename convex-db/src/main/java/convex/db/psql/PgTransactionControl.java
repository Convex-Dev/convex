package convex.db.psql;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Recognises PostgreSQL's transaction-control statements. The wire protocol has no message
 * for them: clients send them as SQL text, and Calcite has no grammar for them, so the
 * server classifies them by their leading keywords before any statement reaches the parser.
 *
 * <p>{@code BEGIN}, {@code START TRANSACTION}, {@code COMMIT}, {@code END},
 * {@code ROLLBACK} and {@code ABORT} are recognised, with the optional {@code WORK} or
 * {@code TRANSACTION} and the {@code READ COMMITTED} and {@code REPEATABLE READ} isolation
 * levels, {@code READ WRITE} and {@code [NOT] DEFERRABLE} modes. A transaction then is a
 * fork of the database, reading a snapshot and committed by the database's merge policy.
 * Forms the server cannot honour are refused with SQLSTATE 0A000 rather than ignored:
 * {@code SERIALIZABLE}, {@code READ ONLY}, savepoints, chained transactions and two-phase
 * commit.
 */
public final class PgTransactionControl {

	/** What a recognised statement does to the transaction. */
	public enum Kind {
		/** Starts a transaction block. */
		BEGIN,
		/** Ends the block, committing it. */
		COMMIT,
		/** Ends the block, discarding it. */
		ROLLBACK
	}

	private PgTransactionControl() {}

	/**
	 * Classifies a statement.
	 *
	 * @param sql One SQL statement
	 * @return Its kind, or null if it is not transaction control
	 * @throws SQLException with SQLSTATE 0A000 for a transaction-control statement the
	 *         server does not support
	 */
	public static Kind classify(String sql) throws SQLException {
		List<String> words = words(sql);
		if (words.isEmpty()) return null;
		String first = words.get(0);
		return switch (first) {
			case "BEGIN" -> begin(words, 1);
			case "START" -> (words.size() > 1 && words.get(1).equals("TRANSACTION")) ? begin(words, 2) : null;
			case "COMMIT", "END" -> {
				if (first.equals("COMMIT") && words.size() > 1 && words.get(1).equals("PREPARED")) {
					throw unsupported("Two-phase commit");
				}
				end(words);
				yield Kind.COMMIT;
			}
			case "ROLLBACK", "ABORT" -> {
				if (first.equals("ROLLBACK") && words.size() > 1 && words.get(1).equals("PREPARED")) {
					throw unsupported("Two-phase commit");
				}
				if (afterWorkOrTransaction(words).contains("TO")) throw unsupported("Savepoints are");
				end(words);
				yield Kind.ROLLBACK;
			}
			case "SAVEPOINT", "RELEASE" -> throw unsupported("Savepoints are");
			case "PREPARE" -> {
				if (words.size() > 1 && words.get(1).equals("TRANSACTION")) throw unsupported("Two-phase commit");
				yield null;
			}
			default -> null;
		};
	}

	private static Kind begin(List<String> words, int from) throws SQLException {
		List<String> modes = words.subList(from, words.size());
		if (!modes.isEmpty() && (modes.get(0).equals("WORK") || modes.get(0).equals("TRANSACTION"))) {
			modes = modes.subList(1, modes.size());
		}
		for (int i = 0; i + 1 < modes.size(); i++) {
			if (modes.get(i).equals("READ") && modes.get(i + 1).equals("ONLY")) {
				throw unsupported("Read-only transactions are");
			}
			// A transaction reads a snapshot and merges at commit: repeatable read, not serialisable
			if (modes.get(i).equals("LEVEL") && modes.get(i + 1).equals("SERIALIZABLE")) {
				throw unsupported("Serializable isolation is");
			}
		}
		return Kind.BEGIN;
	}

	private static void end(List<String> words) throws SQLException {
		List<String> rest = afterWorkOrTransaction(words);
		if (rest.contains("CHAIN") && !rest.contains("NO")) throw unsupported("Chained transactions are");
	}

	private static List<String> afterWorkOrTransaction(List<String> words) {
		List<String> rest = words.subList(1, words.size());
		if (!rest.isEmpty() && (rest.get(0).equals("WORK") || rest.get(0).equals("TRANSACTION"))) {
			rest = rest.subList(1, rest.size());
		}
		return rest;
	}

	/** The statement's words, upper-cased, without a trailing semicolon. */
	private static List<String> words(String sql) {
		List<String> words = new ArrayList<>();
		if (sql == null) return words;
		String s = sql.strip();
		while (s.endsWith(";")) s = s.substring(0, s.length() - 1).strip();
		for (String w : s.split("[\\s,]+")) {
			if (!w.isEmpty()) words.add(w.toUpperCase(Locale.ROOT));
		}
		return words;
	}

	private static SQLException unsupported(String what) {
		return new SQLException(what + " not supported", "0A000");
	}
}
