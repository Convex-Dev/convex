package convex.db.calcite;

import java.io.Reader;
import java.util.ArrayList;
import java.util.List;

import org.apache.calcite.jdbc.CalcitePrepare;
import org.apache.calcite.jdbc.CalciteSchema;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeField;
import org.apache.calcite.schema.Schema;
import org.apache.calcite.server.DdlExecutorImpl;
import org.apache.calcite.server.DdlExecutor;
import org.apache.calcite.sql.SqlIdentifier;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.SqlUtil;
import org.apache.calcite.sql.ddl.SqlColumnDeclaration;
import org.apache.calcite.sql.ddl.SqlCreateTable;
import org.apache.calcite.sql.ddl.SqlDropObject;
import org.apache.calcite.sql.parser.SqlAbstractParserImpl;
import org.apache.calcite.sql.parser.SqlParserImplFactory;
import org.apache.calcite.sql.parser.ddl.SqlDdlParserImpl;
import org.apache.calcite.jdbc.ContextSqlValidator;
import org.apache.calcite.sql.validate.SqlValidator;
import org.apache.calcite.util.Pair;
import org.apache.calcite.util.Util;

import static java.util.Objects.requireNonNull;
import static org.apache.calcite.util.Static.RESOURCE;

/**
 * DDL executor that creates Convex lattice-backed tables instead of
 * Calcite's default in-memory MutableArrayTable.
 *
 * <p>Supports CREATE TABLE and DROP TABLE via SQL. Tables are persisted
 * to the lattice cursor tree and participate in lattice replication.
 */
public class ConvexDdlExecutor extends DdlExecutorImpl {

	public static final ConvexDdlExecutor INSTANCE = new ConvexDdlExecutor();

	public static final SqlParserImplFactory PARSER_FACTORY =
		new SqlParserImplFactory() {
			@Override public SqlAbstractParserImpl getParser(Reader stream) {
				return SqlDdlParserImpl.FACTORY.getParser(stream);
			}

			@Override public DdlExecutor getDdlExecutor() {
				return ConvexDdlExecutor.INSTANCE;
			}
		};

	protected ConvexDdlExecutor() {}

	/**
	 * Executes CREATE TABLE by creating a Convex lattice-backed table.
	 */
	public void execute(SqlCreateTable create, CalcitePrepare.Context context) {
		final Pair<CalciteSchema, String> pair = schema(context, create.name);
		CalciteSchema schema = requireNonNull(pair.left, "schema");
		String tableName = pair.right;

		// Check if table already exists
		if (schema.plus().tables().get(tableName) != null) {
			if (create.ifNotExists) return;
			if (!create.getReplace()) {
				throw SqlUtil.newContextException(create.name.getParserPosition(),
						RESOURCE.tableExists(tableName));
			}
		}

		// Extract column names and types from the DDL
		if (create.columnList == null) {
			throw SqlUtil.newContextException(create.name.getParserPosition(),
					RESOURCE.createTableRequiresColumnList());
		}

		List<String> columnNames = new ArrayList<>();
		List<ConvexColumnType> columnTypes = new ArrayList<>();
		SqlValidator validator = new ContextSqlValidator(context, true);

		for (SqlNode node : create.columnList) {
			if (node instanceof SqlColumnDeclaration col) {
				columnNames.add(col.name.getSimple());
				RelDataType relType = col.dataType.deriveType(validator, true);
				columnTypes.add(ConvexColumnType.fromRelDataType(relType));
			} else if (node instanceof SqlIdentifier id) {
				columnNames.add(id.getSimple());
				columnTypes.add(ConvexColumnType.of(ConvexType.ANY));
			}
		}

		// Find the ConvexSchema and create the table in the lattice
		Schema unwrapped = schema.plus().unwrap(ConvexSchema.class);
		if (unwrapped instanceof ConvexSchema convexSchema) {
			convexSchema.getTables().createTable(tableName,
					columnNames.toArray(new String[0]),
					columnTypes.toArray(new ConvexColumnType[0]));
			// Add to Calcite's schema so it's immediately visible
			schema.plus().add(tableName, new ConvexTable(convexSchema, tableName));
		} else {
			throw new IllegalStateException(
					"CREATE TABLE requires a ConvexSchema, got: " + schema.plus().getClass());
		}
	}

	/**
	 * Executes DROP TABLE / DROP VIEW / DROP SCHEMA etc.
	 */
	public void execute(SqlDropObject drop, CalcitePrepare.Context context) {
		final Pair<CalciteSchema, String> pair = schema(context, drop.name);
		CalciteSchema schema = pair.left;
		String name = pair.right;

		if (schema == null) {
			if (drop.ifExists) return;
			throw SqlUtil.newContextException(drop.name.getParserPosition(),
					RESOURCE.objectNotFoundWithin(name, "schema"));
		}

		switch (drop.getKind()) {
		case DROP_TABLE:
			// Drop from lattice
			Schema unwrapped = schema.plus().unwrap(ConvexSchema.class);
			if (unwrapped instanceof ConvexSchema convexSchema) {
				if (!convexSchema.dropTable(name)) {
					if (!drop.ifExists) {
						throw SqlUtil.newContextException(drop.name.getParserPosition(),
								RESOURCE.objectNotFound(name));
					}
				}
			}
			// Remove from Calcite
			schema.removeTable(name);
			break;
		default:
			throw new UnsupportedOperationException("DROP " + drop.getKind() + " not supported");
		}
	}

	private static Pair<CalciteSchema, String> schema(
			CalcitePrepare.Context context, SqlIdentifier id) {
		final String name;
		final List<String> path;
		if (id.isSimple()) {
			path = context.getDefaultSchemaPath();
			name = id.getSimple();
		} else {
			path = Util.skipLast(id.names);
			name = Util.last(id.names);
		}
		CalciteSchema schema = context.getMutableRootSchema();
		for (String p : path) {
			CalciteSchema sub = schema.getSubSchema(p, true);
			if (sub == null) return Pair.of(null, name);
			schema = sub;
		}
		return Pair.of(schema, name);
	}
}
