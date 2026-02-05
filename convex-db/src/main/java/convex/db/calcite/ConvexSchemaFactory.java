package convex.db.calcite;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.calcite.schema.Schema;
import org.apache.calcite.schema.SchemaFactory;
import org.apache.calcite.schema.SchemaPlus;

import convex.db.lattice.LatticeTables;
import convex.db.lattice.SQLDatabase;

/**
 * Factory for creating Convex schemas.
 *
 * <p>Can be used in a Calcite model JSON file:
 * <pre>
 * {
 *   "version": "1.0",
 *   "schemas": [{
 *     "name": "convex",
 *     "type": "custom",
 *     "factory": "convex.db.calcite.ConvexSchemaFactory",
 *     "operand": {
 *       "database": "mydb"
 *     }
 *   }]
 * }
 * </pre>
 *
 * <p>Or via JDBC URL:
 * <pre>
 * jdbc:convex:database=mydb
 * </pre>
 *
 * <p>Databases must be registered before connecting:
 * <pre>
 * SQLDatabase db = SQLDatabase.create("mydb", keyPair);
 * ConvexSchemaFactory.register("mydb", db);
 *
 * Connection conn = DriverManager.getConnection("jdbc:convex:database=mydb");
 * </pre>
 */
public class ConvexSchemaFactory implements SchemaFactory {

	/** Registry of databases by name */
	private static final Map<String, SQLDatabase> REGISTRY = new ConcurrentHashMap<>();

	/**
	 * Registers a database for JDBC access.
	 *
	 * @param name Database name (used in connection URL)
	 * @param database The SQLDatabase instance
	 */
	public static void register(String name, SQLDatabase database) {
		REGISTRY.put(name, database);
	}

	/**
	 * Unregisters a database.
	 *
	 * @param name Database name
	 * @return The removed database, or null if not found
	 */
	public static SQLDatabase unregister(String name) {
		return REGISTRY.remove(name);
	}

	/**
	 * Gets a registered database.
	 *
	 * @param name Database name
	 * @return The database, or null if not registered
	 */
	public static SQLDatabase get(String name) {
		return REGISTRY.get(name);
	}

	/**
	 * Creates a ConvexSchema from a registered database.
	 *
	 * @param name Database name
	 * @return ConvexSchema, or null if database not registered
	 */
	public static ConvexSchema createFromRegistry(String name) {
		SQLDatabase db = REGISTRY.get(name);
		if (db == null) return null;
		return new ConvexSchema(db.tables(), name);
	}

	// ========== Table lookup for code generation ==========

	/** Cache of schemas for table lookup */
	private static final Map<String, ConvexSchema> SCHEMA_CACHE = new ConcurrentHashMap<>();

	/**
	 * Gets a ConvexTable by schema and table name.
	 * Called from generated code in ConvexTableModify.
	 *
	 * @param schemaName Schema name
	 * @param tableName Table name
	 * @return The ConvexTable
	 */
	public static ConvexTable getTable(String schemaName, String tableName) {
		ConvexSchema schema = SCHEMA_CACHE.computeIfAbsent(schemaName, k -> {
			SQLDatabase db = REGISTRY.get(k);
			if (db == null) {
				throw new IllegalStateException("Database '" + k + "' not registered");
			}
			return new ConvexSchema(db.tables(), k);
		});
		return schema.getConvexTable(tableName);
	}

	@Override
	public Schema create(SchemaPlus parentSchema, String name, Map<String, Object> operand) {
		// Get database name from operand
		String dbName = (String) operand.get("database");
		if (dbName == null) {
			dbName = name;
		}

		// Look up in registry
		SQLDatabase db = REGISTRY.get(dbName);
		if (db != null) {
			return new ConvexSchema(db.tables(), name);
		}

		throw new IllegalArgumentException(
			"Database '" + dbName + "' not registered. Call ConvexSchemaFactory.register() first.");
	}
}
