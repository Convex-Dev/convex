package convex.db.calcite;

import java.util.Map;

import org.apache.calcite.schema.Schema;
import org.apache.calcite.schema.SchemaFactory;
import org.apache.calcite.schema.SchemaPlus;

import convex.db.lattice.LatticeTables;
import convex.db.lattice.SQLDatabase;

/**
 * Factory for creating Convex schemas from Calcite model configuration.
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
 * <p>Or used programmatically by providing a LatticeTables instance directly.
 */
public class ConvexSchemaFactory implements SchemaFactory {

	// Thread-local storage for programmatic schema creation
	private static final ThreadLocal<LatticeTables> TABLES_CONTEXT = new ThreadLocal<>();

	/**
	 * Creates a schema from model configuration.
	 *
	 * <p>If a LatticeTables instance was set via {@link #withTables}, uses that.
	 * Otherwise, creates a new SQLDatabase based on the operand configuration.
	 *
	 * @param parentSchema Parent schema
	 * @param name Schema name
	 * @param operand Configuration from model file
	 * @return New ConvexSchema instance
	 */
	@Override
	public Schema create(SchemaPlus parentSchema, String name, Map<String, Object> operand) {
		// Check for programmatically provided tables
		LatticeTables tables = TABLES_CONTEXT.get();
		if (tables != null) {
			return new ConvexSchema(tables, name);
		}

		// Create from operand configuration
		String dbName = (String) operand.getOrDefault("database", name);
		// For model-based creation, we'd need to resolve the database
		// This is a placeholder - real implementation would look up or create the database
		throw new UnsupportedOperationException(
			"Model-based schema creation not yet implemented. Use SQLEngine.create() for programmatic access.");
	}

	/**
	 * Sets the LatticeTables to use for the next schema creation.
	 *
	 * <p>This enables programmatic schema creation without a model file.
	 * The tables are stored in thread-local storage and consumed by the
	 * next call to {@link #create}.
	 *
	 * @param tables LatticeTables to use
	 */
	public static void withTables(LatticeTables tables) {
		TABLES_CONTEXT.set(tables);
	}

	/**
	 * Clears the thread-local tables context.
	 */
	public static void clearContext() {
		TABLES_CONTEXT.remove();
	}
}
