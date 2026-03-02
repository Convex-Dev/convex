package convex.db.calcite.pgcatalog;

import java.util.HashMap;
import java.util.Map;

import org.apache.calcite.schema.Table;
import org.apache.calcite.schema.impl.AbstractSchema;

import convex.db.calcite.ConvexSchema;

/**
 * Virtual pg_catalog schema providing PostgreSQL system catalog tables.
 *
 * <p>This enables PostgreSQL client tools (DBeaver, DataGrip, Metabase, etc.)
 * to introspect the database structure.
 */
public class PgCatalogSchema extends AbstractSchema {

	private final ConvexSchema convexSchema;
	private final Map<String, Table> tableMap;

	public PgCatalogSchema(ConvexSchema convexSchema) {
		this.convexSchema = convexSchema;
		this.tableMap = new HashMap<>();
		tableMap.put("pg_type", new PgTypeTable());
		tableMap.put("pg_namespace", new PgNamespaceTable());
		tableMap.put("pg_class", new PgClassTable(convexSchema));
		tableMap.put("pg_attribute", new PgAttributeTable(convexSchema));
		tableMap.put("pg_database", new PgDatabaseTable());
		tableMap.put("pg_tables", new PgTablesTable(convexSchema));
	}

	@Override
	protected Map<String, Table> getTableMap() {
		return tableMap;
	}

	/**
	 * Gets the underlying Convex schema for table introspection.
	 */
	public ConvexSchema getConvexSchema() {
		return convexSchema;
	}
}
