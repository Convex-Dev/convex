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

/**
 * Virtual pg_catalog.pg_database table.
 *
 * <p>Provides database information for PostgreSQL compatibility.
 */
public class PgDatabaseTable extends AbstractTable implements ScannableTable {

	@Override
	public RelDataType getRowType(RelDataTypeFactory typeFactory) {
		return typeFactory.builder()
			.add("oid", SqlTypeName.INTEGER)
			.add("datname", SqlTypeName.VARCHAR)
			.add("datdba", SqlTypeName.INTEGER)
			.add("encoding", SqlTypeName.INTEGER)
			.add("datcollate", SqlTypeName.VARCHAR)
			.add("datctype", SqlTypeName.VARCHAR)
			.add("datistemplate", SqlTypeName.BOOLEAN)
			.add("datallowconn", SqlTypeName.BOOLEAN)
			.build();
	}

	@Override
	public Enumerable<Object[]> scan(DataContext root) {
		// Return a single "convex" database
		List<Object[]> rows = new ArrayList<>();
		rows.add(new Object[]{
			16384,          // oid
			"convex",       // datname
			10,             // datdba (owner)
			6,              // encoding (UTF8)
			"en_US.UTF-8",  // datcollate
			"en_US.UTF-8",  // datctype
			false,          // datistemplate
			true            // datallowconn
		});
		return Linq4j.asEnumerable(rows);
	}
}
