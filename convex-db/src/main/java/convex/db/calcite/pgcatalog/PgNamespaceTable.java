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
 * Virtual pg_catalog.pg_namespace table.
 *
 * <p>Provides schema (namespace) information for PostgreSQL compatibility.
 */
public class PgNamespaceTable extends AbstractTable implements ScannableTable {

	// Standard namespace OIDs
	public static final int OID_PG_CATALOG = 11;
	public static final int OID_PUBLIC = 2200;
	public static final int OID_INFO_SCHEMA = 13671;

	@Override
	public RelDataType getRowType(RelDataTypeFactory typeFactory) {
		return typeFactory.builder()
			.add("oid", SqlTypeName.INTEGER)
			.add("nspname", SqlTypeName.VARCHAR)
			.add("nspowner", SqlTypeName.INTEGER)
			.add("nspacl", SqlTypeName.VARCHAR)
			.build();
	}

	@Override
	public Enumerable<Object[]> scan(DataContext root) {
		List<Object[]> rows = Arrays.asList(
			new Object[]{OID_PG_CATALOG, "pg_catalog", 10, null},
			new Object[]{OID_PUBLIC, "public", 10, null},
			new Object[]{OID_INFO_SCHEMA, "information_schema", 10, null}
		);
		return Linq4j.asEnumerable(rows);
	}
}
