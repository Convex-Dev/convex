package convex.db.jdbc;

import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.Properties;

import convex.core.data.ACell;
import convex.core.data.Keyword;
import convex.db.lattice.SQLDatabase;

/**
 * How SQL identifiers resolve in a database: the {@code :identifiers} setting of its
 * {@code :config} section. Unquoted names fold to the policy's case; quoted names keep
 * the case they are written in. Every connection applies the policy the database holds
 * when the connection opens, and the PostgreSQL wire server inherits it.
 *
 * <p>Table names are stored as SQL resolves them, so {@code CREATE TABLE orders}
 * stores {@code ORDERS} under the default policy. The lattice API takes names
 * exactly as given.
 */
public enum IdentifierPolicy {

	/**
	 * The SQL standard and the default: unquoted names fold to upper case, and names
	 * compare exactly.
	 */
	UPPER("upper", "TO_UPPER", true),

	/** Unquoted names fold to lower case, and names compare exactly, as in PostgreSQL. */
	LOWER("lower", "TO_LOWER", true),

	/** Unquoted names fold to upper case, and names match whatever their case. */
	INSENSITIVE("insensitive", "TO_UPPER", false);

	/** Key of this setting in a database's {@code :config} section. */
	public static final Keyword KEY = Keyword.intern("identifiers");

	private final Keyword keyword;
	private final String unquotedCasing;
	private final boolean caseSensitive;

	IdentifierPolicy(String name, String unquotedCasing, boolean caseSensitive) {
		this.keyword = Keyword.intern(name);
		this.unquotedCasing = unquotedCasing;
		this.caseSensitive = caseSensitive;
	}

	/** Returns the keyword that names this policy in a database's settings. */
	public Keyword keyword() {
		return keyword;
	}

	/**
	 * Returns the policy a database holds, the default when it holds none.
	 *
	 * @param db Database
	 * @return Identifier policy of the database
	 * @throws SQLException with SQLSTATE 0A000 if the database holds a value this
	 *         implementation does not know
	 */
	public static IdentifierPolicy of(SQLDatabase db) throws SQLException {
		ACell value = db.getSetting(KEY);
		if (value == null) return UPPER;
		for (IdentifierPolicy policy : values()) {
			if (policy.keyword.equals(value)) return policy;
		}
		throw new SQLFeatureNotSupportedException("Unsupported identifier policy " + value + " for database " + db.getName(), "0A000");
	}

	/**
	 * Sets this policy in a database's settings, for connections opened from then on.
	 *
	 * @param db Database
	 */
	public void applyTo(SQLDatabase db) {
		db.configure(KEY, keyword);
	}

	/** Sets the Calcite connection properties that implement this policy. */
	void setProperties(Properties props) {
		props.setProperty("unquotedCasing", unquotedCasing);
		props.setProperty("quotedCasing", "UNCHANGED");
		props.setProperty("caseSensitive", Boolean.toString(caseSensitive));
	}
}
