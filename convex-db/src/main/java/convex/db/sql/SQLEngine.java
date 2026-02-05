package convex.db.sql;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import org.apache.calcite.jdbc.CalciteConnection;
import org.apache.calcite.schema.SchemaPlus;

import convex.db.lattice.LatticeTables;
import convex.db.lattice.SQLDatabase;

/**
 * SQL query execution engine backed by Apache Calcite.
 *
 * <p>Provides a simple API for executing SQL queries against Convex lattice tables.
 *
 * <p>Usage:
 * <pre>
 * SQLDatabase db = SQLDatabase.create("mydb", keyPair);
 * db.tables().createTable("users", new String[]{"id", "name"});
 * db.tables().insert("users", CVMLong.create(1), Vectors.of(...));
 *
 * SQLEngine engine = SQLEngine.create(db);
 * List&lt;Object[]&gt; results = engine.query("SELECT * FROM users");
 * </pre>
 */
public class SQLEngine implements AutoCloseable {

	private final Connection connection;
	private final ConvexSchema schema;
	private final String schemaName;

	private SQLEngine(Connection connection, ConvexSchema schema, String schemaName) {
		this.connection = connection;
		this.schema = schema;
		this.schemaName = schemaName;
	}

	/**
	 * Creates a new SQLEngine for the given database.
	 *
	 * @param database The SQLDatabase to query
	 * @return New SQLEngine instance
	 * @throws SQLException if connection fails
	 */
	public static SQLEngine create(SQLDatabase database) throws SQLException {
		return create(database.tables(), database.getName().toString());
	}

	/**
	 * Creates a new SQLEngine for the given tables.
	 *
	 * @param tables The LatticeTables to query
	 * @param schemaName Schema name for Calcite
	 * @return New SQLEngine instance
	 * @throws SQLException if connection fails
	 */
	public static SQLEngine create(LatticeTables tables, String schemaName) throws SQLException {
		Properties props = new Properties();
		props.setProperty("caseSensitive", "false");

		Connection connection = DriverManager.getConnection("jdbc:calcite:", props);
		CalciteConnection calciteConnection = connection.unwrap(CalciteConnection.class);

		SchemaPlus rootSchema = calciteConnection.getRootSchema();
		ConvexSchema convexSchema = new ConvexSchema(tables);
		rootSchema.add(schemaName, convexSchema);

		// Set as default schema
		calciteConnection.setSchema(schemaName);

		return new SQLEngine(connection, convexSchema, schemaName);
	}

	/**
	 * Executes a SQL query and returns results as a list of Object arrays.
	 *
	 * @param sql SQL query string
	 * @return List of rows, each row is an Object array of column values
	 * @throws SQLException if query fails
	 */
	public List<Object[]> query(String sql) throws SQLException {
		List<Object[]> results = new ArrayList<>();

		try (Statement stmt = connection.createStatement();
			 ResultSet rs = stmt.executeQuery(sql)) {

			ResultSetMetaData meta = rs.getMetaData();
			int columnCount = meta.getColumnCount();

			while (rs.next()) {
				Object[] row = new Object[columnCount];
				for (int i = 0; i < columnCount; i++) {
					row[i] = rs.getObject(i + 1);
				}
				results.add(row);
			}
		}

		return results;
	}

	/**
	 * Executes a SQL update statement (INSERT, UPDATE, DELETE, CREATE, DROP).
	 *
	 * <p>Note: For lattice tables, DDL and DML should be done through
	 * LatticeTables methods. This method is for compatibility.
	 *
	 * @param sql SQL statement
	 * @return Number of rows affected
	 * @throws SQLException if statement fails
	 */
	public int execute(String sql) throws SQLException {
		try (Statement stmt = connection.createStatement()) {
			return stmt.executeUpdate(sql);
		}
	}

	/**
	 * Gets the underlying JDBC connection for direct access.
	 *
	 * @return JDBC Connection
	 */
	public Connection getConnection() {
		return connection;
	}

	/**
	 * Gets the schema name.
	 *
	 * @return Schema name
	 */
	public String getSchemaName() {
		return schemaName;
	}

	/**
	 * Gets the Convex schema.
	 *
	 * @return ConvexSchema
	 */
	public ConvexSchema getSchema() {
		return schema;
	}

	/**
	 * Gets the underlying LatticeTables.
	 *
	 * @return LatticeTables
	 */
	public LatticeTables getTables() {
		return schema.getTables();
	}

	@Override
	public void close() throws SQLException {
		connection.close();
	}
}
