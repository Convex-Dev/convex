package convex.db.calcite;

import java.util.HashMap;
import java.util.Map;

import org.apache.calcite.schema.Schema;
import org.apache.calcite.schema.Table;
import org.apache.calcite.schema.impl.AbstractSchema;

import convex.db.calcite.pgcatalog.PgCatalogSchema;
import convex.db.lattice.SQLTables;

/**
 * Calcite Schema backed by Convex lattice tables.
 *
 * <p>Provides the bridge between Calcite's SQL query engine and Convex's
 * lattice-based table storage. Tables created via DDL are persisted to
 * the underlying LatticeTables instance.
 *
 * <p>This schema is mutable - tables can be added and removed via SQL DDL
 * statements when used with Calcite's DDL executor.
 */
public class ConvexSchema extends AbstractSchema {

	private final SQLTables tables;
	private final String name;

	/**
	 * Creates a new ConvexSchema wrapping the given lattice tables.
	 *
	 * @param tables The LatticeTables backing store
	 * @param name Schema name
	 */
	public ConvexSchema(SQLTables tables, String name) {
		this.tables = tables;
		this.name = name;
	}

	@Override
	public boolean isMutable() {
		return true; // Allow DDL modifications
	}

	@Override
	protected Map<String, Table> getTableMap() {
		Map<String, Table> tableMap = new HashMap<>();
		String[] tableNames = tables.getTableNames();
		for (String tableName : tableNames) {
			tableMap.put(tableName, new ConvexTable(this, tableName));
		}
		return tableMap;
	}

	@Override
	protected Map<String, Schema> getSubSchemaMap() {
		Map<String, Schema> subSchemas = new HashMap<>();
		subSchemas.put("pg_catalog", new PgCatalogSchema(this));
		return subSchemas;
	}

	/**
	 * Gets the underlying LatticeTables instance.
	 *
	 * @return LatticeTables backing store
	 */
	public SQLTables getTables() {
		return tables;
	}

	/**
	 * Gets the schema name.
	 *
	 * @return Schema name
	 */
	public String getName() {
		return name;
	}

	/**
	 * Creates a table in the schema.
	 *
	 * @param tableName Table name
	 * @param columnNames Column names
	 * @return true if table was created, false if it already exists
	 */
	public boolean createTable(String tableName, String... columnNames) {
		return tables.createTable(tableName, columnNames);
	}

	/**
	 * Drops a table from the schema.
	 *
	 * <p>Called by the DDL executor when processing DROP TABLE statements.
	 *
	 * @param tableName Table name
	 * @return true if table was dropped, false if it didn't exist
	 */
	public boolean dropTable(String tableName) {
		return tables.dropTable(tableName);
	}

	/**
	 * Checks if a table exists in the schema.
	 *
	 * @param tableName Table name
	 * @return true if table exists
	 */
	public boolean tableExists(String tableName) {
		return tables.tableExists(tableName);
	}

	/**
	 * Gets a ConvexTable by name.
	 *
	 * @param tableName Table name
	 * @return The ConvexTable
	 */
	public ConvexTable getConvexTable(String tableName) {
		return new ConvexTable(this, tableName);
	}
}
