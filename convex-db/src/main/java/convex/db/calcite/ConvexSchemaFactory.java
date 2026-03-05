package convex.db.calcite;

import java.util.Map;

import org.apache.calcite.schema.Schema;
import org.apache.calcite.schema.SchemaFactory;
import org.apache.calcite.schema.SchemaPlus;

import convex.db.lattice.SQLDatabase;
import convex.db.lattice.SQLSchema;
import convex.lattice.cursor.ALatticeCursor;

/**
 * Factory for creating Convex SQL schemas backed by a lattice cursor.
 *
 * <p>Holds a reference to the database-level lattice cursor. All schema and
 * table resolution navigates the cursor tree — no separate registry or cache.
 *
 * <p>Usage:
 * <pre>
 * SQLDatabase db = SQLDatabase.create("mydb", keyPair);
 * ConvexSchemaFactory.setDatabase(db);
 *
 * Connection conn = DriverManager.getConnection("jdbc:convex:database=mydb");
 * </pre>
 */
public class ConvexSchemaFactory implements SchemaFactory {

	/** The current database. All navigation goes through its cursor tree. */
	private static volatile SQLDatabase database;

	/**
	 * Sets the database for JDBC/SQL access.
	 *
	 * @param db The SQLDatabase instance
	 */
	public static void setDatabase(SQLDatabase db) {
		database = db;
	}

	/**
	 * Gets the current database.
	 *
	 * @return The SQLDatabase, or null if not set
	 */
	public static SQLDatabase getDatabase() {
		return database;
	}

	/**
	 * Creates a ConvexSchema for the current database.
	 *
	 * @param schemaName Schema name for the Calcite schema
	 * @return ConvexSchema, or null if no database set
	 */
	public static ConvexSchema createSchema(String schemaName) {
		SQLDatabase db = database;
		if (db == null) return null;
		return new ConvexSchema(db.tables(), schemaName);
	}

	// ========== Table lookup for generated code ==========

	/**
	 * Gets a ConvexTable by schema and table name.
	 * Called from generated code in ConvexTableModify.
	 * Navigates the database's cursor tree each time, so it always
	 * reflects the current lattice state.
	 *
	 * @param schemaName Schema name (currently maps to the database)
	 * @param tableName Table name
	 * @return The ConvexTable
	 */
	public static ConvexTable getTable(String schemaName, String tableName) {
		SQLDatabase db = database;
		if (db == null) {
			throw new IllegalStateException("No database set. Call ConvexSchemaFactory.setDatabase() first.");
		}
		return new ConvexSchema(db.tables(), schemaName).getConvexTable(tableName);
	}

	@Override
	public Schema create(SchemaPlus parentSchema, String name, Map<String, Object> operand) {
		ConvexSchema schema = createSchema(name);
		if (schema != null) {
			return schema;
		}
		throw new IllegalArgumentException(
			"No database set. Call ConvexSchemaFactory.setDatabase() first.");
	}
}
