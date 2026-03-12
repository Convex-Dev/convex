package convex.db.calcite;

import java.util.Map;

import org.apache.calcite.schema.Schema;
import org.apache.calcite.schema.SchemaFactory;
import org.apache.calcite.schema.SchemaPlus;

import convex.db.ConvexDB;
import convex.db.lattice.SQLDatabase;

/**
 * Calcite {@link SchemaFactory} for Convex SQL databases.
 *
 * <p>Resolves databases from the {@link ConvexDB} registry.
 * All schema and table resolution navigates the cursor tree — no separate cache.
 *
 * <p>Usage:
 * <pre>
 * ConvexDB cdb = ConvexDB.create();
 * SQLDatabase db = cdb.database("mydb");
 * cdb.register("mydb");
 *
 * Connection conn = DriverManager.getConnection("jdbc:convex:database=mydb");
 * </pre>
 */
public class ConvexSchemaFactory implements SchemaFactory {

	/**
	 * Creates a ConvexSchema for the named database.
	 *
	 * @param name Database/schema name
	 * @return ConvexSchema, or null if no database registered with that name
	 */
	public static ConvexSchema createSchema(String name) {
		SQLDatabase db = ConvexDB.lookupDatabase(name);
		if (db == null) return null;
		return new ConvexSchema(db, name);
	}

	// ========== Table lookup for generated code ==========

	/**
	 * Gets a ConvexTable by schema and table name.
	 * Called from generated code in ConvexTableModify.
	 * Navigates the database's cursor tree each time, so it always
	 * reflects the current lattice state.
	 *
	 * @param schemaName Schema name (maps to a registered database)
	 * @param tableName Table name
	 * @return The ConvexTable
	 */
	public static ConvexTable getTable(String schemaName, String tableName) {
		SQLDatabase db = ConvexDB.lookupDatabase(schemaName);
		if (db == null) {
			throw new IllegalStateException(
				"No database registered with name '" + schemaName +
				"'. Register via ConvexDB.register(dbName) first.");
		}
		return new ConvexSchema(db, schemaName).getConvexTable(tableName);
	}

	@Override
	public Schema create(SchemaPlus parentSchema, String name, Map<String, Object> operand) {
		ConvexSchema schema = createSchema(name);
		if (schema != null) {
			return schema;
		}
		throw new IllegalArgumentException(
			"No database registered with name '" + name +
			"'. Register via ConvexDB.register(dbName) first.");
	}
}
