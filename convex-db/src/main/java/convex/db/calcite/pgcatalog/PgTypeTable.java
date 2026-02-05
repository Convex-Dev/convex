package convex.db.calcite.pgcatalog;

import java.util.Arrays;
import java.util.List;

import org.apache.calcite.DataContext;
import org.apache.calcite.linq4j.Enumerable;
import org.apache.calcite.linq4j.Linq4j;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeFactory;
import org.apache.calcite.schema.ScannableTable;
import org.apache.calcite.schema.impl.AbstractTable;
import org.apache.calcite.sql.type.SqlTypeName;

/**
 * Virtual pg_catalog.pg_type table.
 *
 * <p>Provides type OID mappings for PostgreSQL protocol compatibility.
 * These OIDs are used in RowDescription messages to tell clients how to
 * interpret column data.
 */
public class PgTypeTable extends AbstractTable implements ScannableTable {

	// Standard PostgreSQL type OIDs
	public static final int OID_BOOL = 16;
	public static final int OID_BYTEA = 17;
	public static final int OID_INT8 = 20;      // bigint
	public static final int OID_INT2 = 21;      // smallint
	public static final int OID_INT4 = 23;      // integer
	public static final int OID_TEXT = 25;
	public static final int OID_FLOAT4 = 700;   // real
	public static final int OID_FLOAT8 = 701;   // double precision
	public static final int OID_VARCHAR = 1043;
	public static final int OID_DATE = 1082;
	public static final int OID_TIMESTAMP = 1114;
	public static final int OID_NUMERIC = 1700;

	// pg_type namespace OID (pg_catalog = 11)
	private static final int PG_CATALOG_OID = 11;

	@Override
	public RelDataType getRowType(RelDataTypeFactory typeFactory) {
		return typeFactory.builder()
			.add("oid", SqlTypeName.INTEGER)
			.add("typname", SqlTypeName.VARCHAR)
			.add("typnamespace", SqlTypeName.INTEGER)
			.add("typlen", SqlTypeName.SMALLINT)
			.add("typtype", SqlTypeName.CHAR)
			.add("typcategory", SqlTypeName.CHAR)
			.add("typrelid", SqlTypeName.INTEGER)
			.add("typelem", SqlTypeName.INTEGER)
			.add("typarray", SqlTypeName.INTEGER)
			.add("typnotnull", SqlTypeName.BOOLEAN)
			.build();
	}

	@Override
	public Enumerable<Object[]> scan(DataContext root) {
		List<Object[]> rows = Arrays.asList(
			// Boolean
			row(OID_BOOL, "bool", PG_CATALOG_OID, 1, 'b', 'B', 0, 0, 1000, false),
			// Bytea (binary)
			row(OID_BYTEA, "bytea", PG_CATALOG_OID, -1, 'b', 'U', 0, 0, 1001, false),
			// Integers
			row(OID_INT2, "int2", PG_CATALOG_OID, 2, 'b', 'N', 0, 0, 1005, false),
			row(OID_INT4, "int4", PG_CATALOG_OID, 4, 'b', 'N', 0, 0, 1007, false),
			row(OID_INT8, "int8", PG_CATALOG_OID, 8, 'b', 'N', 0, 0, 1016, false),
			// Floating point
			row(OID_FLOAT4, "float4", PG_CATALOG_OID, 4, 'b', 'N', 0, 0, 1021, false),
			row(OID_FLOAT8, "float8", PG_CATALOG_OID, 8, 'b', 'N', 0, 0, 1022, false),
			// Numeric/decimal
			row(OID_NUMERIC, "numeric", PG_CATALOG_OID, -1, 'b', 'N', 0, 0, 1231, false),
			// Text types
			row(OID_TEXT, "text", PG_CATALOG_OID, -1, 'b', 'S', 0, 0, 1009, false),
			row(OID_VARCHAR, "varchar", PG_CATALOG_OID, -1, 'b', 'S', 0, 0, 1015, false),
			// Date/time
			row(OID_DATE, "date", PG_CATALOG_OID, 4, 'b', 'D', 0, 0, 1182, false),
			row(OID_TIMESTAMP, "timestamp", PG_CATALOG_OID, 8, 'b', 'D', 0, 0, 1115, false)
		);
		return Linq4j.asEnumerable(rows);
	}

	private Object[] row(int oid, String name, int namespace, int len, char type, char category,
						 int relid, int elem, int array, boolean notnull) {
		return new Object[]{oid, name, namespace, (short) len, String.valueOf(type),
			String.valueOf(category), relid, elem, array, notnull};
	}

	/**
	 * Maps a SQL type name to a PostgreSQL OID.
	 */
	public static int getOid(SqlTypeName sqlType) {
		return switch (sqlType) {
			case BOOLEAN -> OID_BOOL;
			case TINYINT, SMALLINT -> OID_INT2;
			case INTEGER -> OID_INT4;
			case BIGINT -> OID_INT8;
			case REAL, FLOAT -> OID_FLOAT4;
			case DOUBLE -> OID_FLOAT8;
			case DECIMAL -> OID_NUMERIC;
			case CHAR, VARCHAR -> OID_VARCHAR;
			case BINARY, VARBINARY -> OID_BYTEA;
			case DATE -> OID_DATE;
			case TIMESTAMP, TIMESTAMP_WITH_LOCAL_TIME_ZONE -> OID_TIMESTAMP;
			default -> OID_TEXT; // Default to text
		};
	}
}
