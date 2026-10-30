package convex.db.psql;

import java.util.ArrayList;
import java.util.List;

/**
 * Finds the parts of PostgreSQL SQL text that are not code, by PostgreSQL's lexical rules:
 * string literals ({@code '...'} with {@code ''} for a quote, and {@code E'...'} with
 * backslash escapes), quoted identifiers ({@code "..."} with {@code ""}), dollar-quoted
 * strings ({@code $tag$...$tag$}), line comments ({@code --}) and block comments, which nest.
 * An unterminated one runs to the end of the text.
 *
 * <p>The server reads a few things from SQL text before Calcite parses it, such as where
 * one statement of a simple query ends; it must not find them inside a literal.
 */
public final class PgSqlScanner {

	private PgSqlScanner() {}

	/**
	 * Splits a simple query into its statements at each semicolon outside literals,
	 * identifiers and comments, as PostgreSQL does.
	 *
	 * @param sql Query text
	 * @return The statements, trimmed, without empty ones
	 */
	public static List<String> split(String sql) {
		List<String> statements = new ArrayList<>();
		int start = 0;
		int i = 0;
		while (i < sql.length()) {
			int end = skip(sql, i);
			if (end > i) {
				i = end;
			} else if (sql.charAt(i) == ';') {
				add(statements, sql.substring(start, i));
				start = ++i;
			} else {
				i++;
			}
		}
		add(statements, sql.substring(start));
		return statements;
	}

	/**
	 * The code of SQL text: literals and quoted identifiers replaced by empty ones and
	 * comments by a space, so that a search of the text finds only code.
	 *
	 * @param sql SQL text
	 * @return The text with only its code
	 */
	public static String code(String sql) {
		StringBuilder code = new StringBuilder(sql.length());
		int i = 0;
		while (i < sql.length()) {
			int end = skip(sql, i);
			if (end > i) {
				char c = sql.charAt(i);
				code.append(c == '"' ? "\"\"" : (c == '-' || c == '/') ? " " : "''");
				i = end;
			} else {
				code.append(sql.charAt(i++));
			}
		}
		return code.toString();
	}

	private static void add(List<String> statements, String statement) {
		String s = statement.strip();
		if (!s.isEmpty()) statements.add(s);
	}

	/**
	 * The end of the literal, quoted identifier or comment starting at {@code i}, or
	 * {@code i} if none starts there.
	 */
	static int skip(String sql, int i) {
		int n = sql.length();
		char c = sql.charAt(i);
		char next = (i + 1 < n) ? sql.charAt(i + 1) : 0;
		if (c == '\'') {
			boolean escapes = i > 0 && (sql.charAt(i - 1) == 'E' || sql.charAt(i - 1) == 'e')
				&& (i < 2 || !isIdentifierPart(sql.charAt(i - 2)));
			return quoted(sql, i + 1, '\'', escapes);
		}
		if (c == '"') return quoted(sql, i + 1, '"', false);
		if (c == '-' && next == '-') {
			int eol = sql.indexOf('\n', i);
			return (eol < 0) ? n : eol + 1;
		}
		if (c == '/' && next == '*') {
			int depth = 0;
			int j = i;
			while (j < n) {
				if (sql.startsWith("/*", j)) { depth++; j += 2; }
				else if (sql.startsWith("*/", j)) { depth--; j += 2; if (depth == 0) return j; }
				else j++;
			}
			return n;
		}
		if (c == '$' && (i == 0 || !isIdentifierPart(sql.charAt(i - 1)))) {
			int j = i + 1;
			if (j < n && Character.isDigit(sql.charAt(j))) return i; // a parameter such as $1
			while (j < n && (Character.isLetterOrDigit(sql.charAt(j)) || sql.charAt(j) == '_')) j++;
			if (j < n && sql.charAt(j) == '$') {
				String tag = sql.substring(i, j + 1);
				int close = sql.indexOf(tag, j + 1);
				return (close < 0) ? n : close + tag.length();
			}
		}
		return i;
	}

	/** The end of a quoted run whose body starts at {@code j}, a doubled quote standing for one. */
	private static int quoted(String sql, int j, char quote, boolean escapes) {
		int n = sql.length();
		while (j < n) {
			char c = sql.charAt(j);
			if (escapes && c == '\\') {
				j += 2;
			} else if (c == quote) {
				if (j + 1 < n && sql.charAt(j + 1) == quote) j += 2;
				else return j + 1;
			} else {
				j++;
			}
		}
		return n;
	}

	private static boolean isIdentifierPart(char c) {
		return Character.isLetterOrDigit(c) || c == '_' || c == '$';
	}
}
