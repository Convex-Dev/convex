package convex.db.calcite;

import static org.apache.calcite.util.Static.RESOURCE;

import java.util.List;
import java.util.regex.Pattern;

import org.apache.calcite.runtime.Resources;
import org.apache.calcite.sql.validate.SqlValidatorException;

/**
 * SQLSTATE codes for Calcite's validation errors. Calcite raises them as a
 * {@link SqlValidatorException} carrying only a formatted message, so each error is
 * recognised by the message template Calcite formatted it from, taken from Calcite's
 * own resources rather than copied here.
 */
public final class ValidationErrors {

	/** PostgreSQL's undefined_table: a statement names a table that does not exist. */
	public static final String UNDEFINED_TABLE = "42P01";

	/** The templates of the errors Calcite raises when a named table does not resolve. */
	private static final List<Pattern> UNDEFINED_TABLE_MESSAGES = List.of(
		pattern(RESOURCE.objectNotFound("")),
		pattern(RESOURCE.objectNotFoundWithin("", "")),
		pattern(RESOURCE.objectNotFoundDidYouMean("", "")),
		pattern(RESOURCE.objectNotFoundWithinDidYouMean("", "", "")),
		pattern(RESOURCE.tableNameNotFound("")),
		pattern(RESOURCE.tableNameNotFoundDidYouMean("", "")));

	private ValidationErrors() {}

	/**
	 * Returns the SQLSTATE of the first Calcite validation error in a cause chain that
	 * has one.
	 *
	 * @param t Exception raised while preparing or executing a statement
	 * @return SQLSTATE, or null if no validation error in the chain has a known code
	 */
	public static String sqlState(Throwable t) {
		for (Throwable c = t; c != null; c = (c.getCause() == c) ? null : c.getCause()) {
			if (c instanceof SqlValidatorException && matches(UNDEFINED_TABLE_MESSAGES, c.getMessage())) {
				return UNDEFINED_TABLE;
			}
		}
		return null;
	}

	private static boolean matches(List<Pattern> patterns, String message) {
		if (message == null) return false;
		for (Pattern p : patterns) {
			if (p.matcher(message).matches()) return true;
		}
		return false;
	}

	/**
	 * Converts a resource's {@link java.text.MessageFormat} template to a pattern
	 * matching any message formatted from it: each argument matches any text, and the
	 * rest matches literally, with the template's quoting undone.
	 */
	static Pattern pattern(Resources.Inst resource) {
		String template = resource.raw();
		StringBuilder regex = new StringBuilder("(?s)");
		StringBuilder literal = new StringBuilder();
		boolean quoted = false;
		for (int i = 0; i < template.length(); i++) {
			char ch = template.charAt(i);
			if (ch == '\'') {
				if (i + 1 < template.length() && template.charAt(i + 1) == '\'') {
					literal.append('\'');
					i++;
				} else {
					quoted = !quoted;
				}
			} else if (ch == '{' && !quoted) {
				int end = template.indexOf('}', i);
				if (end < 0) throw new IllegalArgumentException("Unterminated argument in " + template);
				regex.append(Pattern.quote(literal.toString())).append(".*");
				literal.setLength(0);
				i = end;
			} else {
				literal.append(ch);
			}
		}
		regex.append(Pattern.quote(literal.toString()));
		return Pattern.compile(regex.toString());
	}
}
