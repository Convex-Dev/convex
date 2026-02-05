package convex.db.calcite;

import java.util.Map;

import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeFactory;
import org.apache.calcite.schema.SchemaPlus;
import org.apache.calcite.schema.Table;
import org.apache.calcite.schema.TableFactory;

/**
 * Factory for creating Convex tables.
 *
 * <p>Can be used in a Calcite model file:
 * <pre>
 * {
 *   "version": "1.0",
 *   "schemas": [{
 *     "name": "convex",
 *     "type": "custom",
 *     "factory": "convex.db.calcite.ConvexSchemaFactory",
 *     "tables": [{
 *       "name": "users",
 *       "type": "custom",
 *       "factory": "convex.db.calcite.ConvexTableFactory",
 *       "operand": {
 *         "columns": ["id", "name", "email"]
 *       }
 *     }]
 *   }]
 * }
 * </pre>
 *
 * <p>Or programmatically:
 * <pre>
 * schema.createTable("users", "id", "name", "email");
 * </pre>
 */
public class ConvexTableFactory implements TableFactory<Table> {

	public static final ConvexTableFactory INSTANCE = new ConvexTableFactory();

	@Override
	public Table create(SchemaPlus schema, String name,
			Map<String, Object> operand, RelDataType rowType) {

		// Find the ConvexSchema
		ConvexSchema convexSchema = findConvexSchema(schema);
		if (convexSchema == null) {
			throw new IllegalStateException("ConvexTableFactory requires a ConvexSchema parent");
		}

		// Get column names from operand or rowType
		String[] columns = extractColumns(operand, rowType);

		// Create table in lattice storage
		convexSchema.createTable(name, columns);

		// Return the table wrapper
		return new ConvexTable(convexSchema, name);
	}

	private ConvexSchema findConvexSchema(SchemaPlus schema) {
		if (schema == null) return null;

		// Check if this schema wraps a ConvexSchema
		try {
			var unwrapped = schema.unwrap(ConvexSchema.class);
			if (unwrapped != null) return unwrapped;
		} catch (Exception e) {
			// Not a ConvexSchema wrapper
		}

		// Check parent
		return findConvexSchema(schema.getParentSchema());
	}

	private String[] extractColumns(Map<String, Object> operand, RelDataType rowType) {
		// Try operand first
		if (operand != null && operand.containsKey("columns")) {
			Object cols = operand.get("columns");
			if (cols instanceof String[] arr) {
				return arr;
			}
			if (cols instanceof java.util.List<?> list) {
				return list.stream()
					.map(Object::toString)
					.toArray(String[]::new);
			}
		}

		// Fall back to rowType
		if (rowType != null) {
			return rowType.getFieldNames().toArray(new String[0]);
		}

		throw new IllegalArgumentException("Table columns must be specified via 'columns' operand or rowType");
	}
}
