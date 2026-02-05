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

import convex.db.calcite.ConvexColumnType;
import convex.db.calcite.ConvexSchema;

/**
 * Virtual pg_catalog.pg_attribute table.
 *
 * <p>Provides column information for PostgreSQL compatibility.
 * Each row represents a column in a table.
 */
public class PgAttributeTable extends AbstractTable implements ScannableTable {

	private final ConvexSchema convexSchema;
	private static final AtomicInteger tableOidCounter = new AtomicInteger(16384);

	public PgAttributeTable(ConvexSchema convexSchema) {
		this.convexSchema = convexSchema;
	}

	@Override
	public RelDataType getRowType(RelDataTypeFactory typeFactory) {
		return typeFactory.builder()
			.add("attrelid", SqlTypeName.INTEGER)    // table OID
			.add("attname", SqlTypeName.VARCHAR)     // column name
			.add("atttypid", SqlTypeName.INTEGER)    // type OID
			.add("attnum", SqlTypeName.SMALLINT)     // column number (1-based)
			.add("attlen", SqlTypeName.SMALLINT)     // type length
			.add("attnotnull", SqlTypeName.BOOLEAN)  // not null constraint
			.add("attisdropped", SqlTypeName.BOOLEAN) // column is dropped
			.add("atttypmod", SqlTypeName.INTEGER)   // type modifier (e.g., varchar length)
			.build();
	}

	@Override
	public Enumerable<Object[]> scan(DataContext root) {
		List<Object[]> rows = new ArrayList<>();

		if (convexSchema != null && convexSchema.getTables() != null) {
			// Reset counter to match pg_class
			tableOidCounter.set(16384);

			for (String tableName : convexSchema.getTables().getTableNames()) {
				int tableOid = tableOidCounter.incrementAndGet();
				String[] columns = convexSchema.getTables().getColumnNames(tableName);
				ConvexColumnType[] types = convexSchema.getTables().getColumnTypes(tableName);

				if (columns != null) {
					for (int i = 0; i < columns.length; i++) {
						int typeOid = PgTypeTable.OID_TEXT; // default
						short typeLen = -1;
						int typeMod = -1;

						if (types != null && i < types.length) {
							typeOid = PgTypeTable.getOid(types[i].getSqlTypeName());
							if (types[i].hasPrecision()) {
								typeMod = types[i].getPrecision() + 4; // PostgreSQL adds 4 for varchar
							}
						}

						rows.add(new Object[]{
							tableOid,                    // attrelid
							columns[i],                  // attname
							typeOid,                     // atttypid
							(short) (i + 1),             // attnum (1-based)
							typeLen,                     // attlen
							i == 0,                      // attnotnull (primary key is not null)
							false,                       // attisdropped
							typeMod                      // atttypmod
						});
					}
				}
			}
		}

		return Linq4j.asEnumerable(rows);
	}
}
