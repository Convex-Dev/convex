package convex.db.calcite;

import java.util.Map;

import org.apache.calcite.DataContext;
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
	 * @param schemaName Schema name (maps to a database)
	 * @param tableName Table name
	 * @return The ConvexTable
	 */
	public static ConvexTable getTable(String schemaName, String tableName) {
		SQLDatabase db = ConvexDB.lookupDatabase(schemaName);
		if (db == null) {
			throw new IllegalStateException(
				"No database found for '" + schemaName + "'.");
		}
		return new ConvexSchema(db, schemaName).getConvexTable(tableName);
	}

	/**
	 * Gets a table from the schema attached to the current connection's data context.
	 * This preserves instance and transaction isolation without a global name lookup.
	 *
	 * @param context Current statement data context
	 * @param schemaName Schema name
	 * @param tableName Table name
	 * @return The connection-local ConvexTable
	 */
	public static ConvexTable getTable(DataContext context, String schemaName, String tableName) {
		if (context == null) throw new IllegalStateException("No data context for DML table lookup");
		SchemaPlus root=context.getRootSchema();
		SchemaPlus schema=(root==null) ? null : root.getSubSchema(schemaName);
		ConvexSchema convexSchema=(schema==null) ? null : schema.unwrap(ConvexSchema.class);
		if (convexSchema==null) {
			throw new IllegalStateException(
					"No Convex schema found for '" + schemaName + "' in the current connection");
		}
		return convexSchema.getConvexTable(tableName);
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
