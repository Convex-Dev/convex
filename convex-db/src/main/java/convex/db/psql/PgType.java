package convex.db.psql;

/**
 * PostgreSQL type OIDs for the wire protocol.
 *
 * <p>These are the standard PostgreSQL type identifiers used in
 * RowDescription messages to indicate column types.
 */
public final class PgType {

	private PgType() {}

	// Numeric types
	public static final int BOOL = 16;
	public static final int BYTEA = 17;
	public static final int INT8 = 20;      // bigint
	public static final int INT2 = 21;      // smallint
	public static final int INT4 = 23;      // integer
	public static final int TEXT = 25;
	public static final int OID = 26;
	public static final int FLOAT4 = 700;   // real
	public static final int FLOAT8 = 701;   // double precision
	public static final int NUMERIC = 1700;

	// String types
	public static final int VARCHAR = 1043;
	public static final int CHAR = 1042;
	public static final int NAME = 19;

	// Date/time types
	public static final int DATE = 1082;
	public static final int TIME = 1083;
	public static final int TIMESTAMP = 1114;
	public static final int TIMESTAMPTZ = 1184;

	// Other
	public static final int JSON = 114;
	public static final int JSONB = 3802;
	public static final int UUID = 2950;
	public static final int UNKNOWN = 705;

	/**
	 * Returns the PostgreSQL type OID for a SQL type name.
	 */
	public static int fromSqlType(String sqlType) {
		if (sqlType == null) return UNKNOWN;

		return switch (sqlType.toUpperCase()) {
			case "BOOLEAN", "BOOL" -> BOOL;
			case "SMALLINT", "INT2" -> INT2;
			case "INTEGER", "INT", "INT4" -> INT4;
			case "BIGINT", "INT8" -> INT8;
			case "REAL", "FLOAT4" -> FLOAT4;
			case "DOUBLE", "DOUBLE PRECISION", "FLOAT8", "FLOAT" -> FLOAT8;
			case "NUMERIC", "DECIMAL" -> NUMERIC;
			case "TEXT" -> TEXT;
			case "VARCHAR", "CHARACTER VARYING" -> VARCHAR;
			case "CHAR", "CHARACTER" -> CHAR;
			case "BYTEA", "BLOB" -> BYTEA;
			case "DATE" -> DATE;
			case "TIME" -> TIME;
			case "TIMESTAMP" -> TIMESTAMP;
			case "TIMESTAMPTZ" -> TIMESTAMPTZ;
			case "JSON" -> JSON;
			case "JSONB" -> JSONB;
			case "UUID" -> UUID;
			default -> UNKNOWN;
		};
	}

	/**
	 * Returns the type length for fixed-size types, or -1 for variable length.
	 */
	public static int typeLength(int oid) {
		return switch (oid) {
			case BOOL -> 1;
			case INT2 -> 2;
			case INT4, OID, FLOAT4 -> 4;
			case INT8, FLOAT8 -> 8;
			default -> -1; // Variable length
		};
	}
}
