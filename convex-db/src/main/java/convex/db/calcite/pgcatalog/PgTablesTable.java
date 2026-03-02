package convex.db.calcite.pgcatalog;

import java.util.ArrayList;
import java.util.List;

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
 * Virtual pg_catalog.pg_tables view.
 *
 * <p>Provides a simplified view of tables for PostgreSQL compatibility.
 * This is commonly queried by database tools to list tables.
 */
public class PgTablesTable extends AbstractTable implements ScannableTable {

	private final ConvexSchema convexSchema;

	public PgTablesTable(ConvexSchema convexSchema) {
		this.convexSchema = convexSchema;
	}

	@Override
	public RelDataType getRowType(RelDataTypeFactory typeFactory) {
		return typeFactory.builder()
			.add("schemaname", SqlTypeName.VARCHAR)
			.add("tablename", SqlTypeName.VARCHAR)
			.add("tableowner", SqlTypeName.VARCHAR)
			.add("tablespace", SqlTypeName.VARCHAR)
			.add("hasindexes", SqlTypeName.BOOLEAN)
			.add("hasrules", SqlTypeName.BOOLEAN)
			.add("hastriggers", SqlTypeName.BOOLEAN)
			.add("rowsecurity", SqlTypeName.BOOLEAN)
			.build();
	}

	@Override
	public Enumerable<Object[]> scan(DataContext root) {
		List<Object[]> rows = new ArrayList<>();

		if (convexSchema != null && convexSchema.getTables() != null) {
			for (String tableName : convexSchema.getTables().getTableNames()) {
				rows.add(new Object[]{
					"public",       // schemaname
					tableName,      // tablename
					"convex",       // tableowner
					null,           // tablespace
					false,          // hasindexes
					false,          // hasrules
					false,          // hastriggers
					false           // rowsecurity
				});
			}
		}

		return Linq4j.asEnumerable(rows);
	}
}
