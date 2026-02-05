package convex.db.calcite.pgcatalog;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.calcite.DataContext;
import org.apache.calcite.linq4j.Enumerable;
import org.apache.calcite.linq4j.Linq4j;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeFactory;
import org.apache.calcite.schema.ScannableTable;
import org.apache.calcite.schema.impl.AbstractTable;
import org.apache.calcite.sql.type.SqlTypeName;

import convex.db.calcite.ConvexSchema;

/**
 * Virtual pg_catalog.pg_class table.
 *
 * <p>Provides table/relation information for PostgreSQL compatibility.
 * Each row represents a table, index, view, or other relation.
 */
public class PgClassTable extends AbstractTable implements ScannableTable {

	private final ConvexSchema convexSchema;
	// OID counter starting after system tables
	private static final AtomicInteger oidCounter = new AtomicInteger(16384);

	public PgClassTable(ConvexSchema convexSchema) {
		this.convexSchema = convexSchema;
	}

	@Override
	public RelDataType getRowType(RelDataTypeFactory typeFactory) {
		return typeFactory.builder()
			.add("oid", SqlTypeName.INTEGER)
			.add("relname", SqlTypeName.VARCHAR)
			.add("relnamespace", SqlTypeName.INTEGER)
			.add("reltype", SqlTypeName.INTEGER)
			.add("relowner", SqlTypeName.INTEGER)
			.add("relkind", SqlTypeName.CHAR)        // 'r' = table, 'i' = index, 'v' = view
			.add("reltuples", SqlTypeName.FLOAT)
			.add("relhasindex", SqlTypeName.BOOLEAN)
			.add("relpersistence", SqlTypeName.CHAR) // 'p' = permanent
			.add("relnatts", SqlTypeName.SMALLINT)   // number of columns
			.build();
	}

	@Override
	public Enumerable<Object[]> scan(DataContext root) {
		List<Object[]> rows = new ArrayList<>();

		if (convexSchema != null && convexSchema.getTables() != null) {
			for (String tableName : convexSchema.getTables().getTableNames()) {
				String[] columns = convexSchema.getTables().getColumnNames(tableName);
				int numColumns = columns != null ? columns.length : 0;

				rows.add(new Object[]{
					oidCounter.incrementAndGet(),  // oid
					tableName,                      // relname
					PgNamespaceTable.OID_PUBLIC,    // relnamespace (public schema)
					0,                              // reltype
					10,                             // relowner (postgres user)
					"r",                            // relkind = ordinary table
					1000.0f,                        // reltuples (estimated)
					false,                          // relhasindex
					"p",                            // relpersistence = permanent
					(short) numColumns              // relnatts
				});
			}
		}

		return Linq4j.asEnumerable(rows);
	}
}
