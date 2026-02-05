package convex.db.jdbc;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Properties;

import org.apache.calcite.jdbc.CalciteConnection;
import org.apache.calcite.jdbc.Driver;
import org.apache.calcite.schema.SchemaPlus;

import convex.db.calcite.ConvexSchema;
import convex.db.calcite.ConvexSchemaFactory;

/**
 * JDBC Driver for Convex SQL databases.
 *
 * <p>Provides standard JDBC connectivity to Convex lattice-backed tables
 * via Apache Calcite.
 *
 * <p>Connection URL format:
 * <pre>
 * jdbc:convex:database=mydb
 * </pre>
 *
 * <p>Usage:
 * <pre>
 * // 1. Create and register database
 * SQLDatabase db = SQLDatabase.create("mydb", keyPair);
 * ConvexSchemaFactory.register("mydb", db);
 *
 * // 2. Connect via JDBC (driver auto-registers)
 * Connection conn = DriverManager.getConnection("jdbc:convex:database=mydb");
 *
 * // 3. Standard JDBC from here
 * Statement stmt = conn.createStatement();
 * ResultSet rs = stmt.executeQuery("SELECT * FROM users");
 * </pre>
 */
public class ConvexDriver extends Driver {

	public static final String PREFIX = "jdbc:convex:";

	static {
		new ConvexDriver().register();
	}

	public ConvexDriver() {
		super();
	}

	@Override
	protected String getConnectStringPrefix() {
		return PREFIX;
	}

	@Override
	public Connection connect(String url, Properties info) throws SQLException {
		if (!acceptsURL(url)) {
			return null;
		}

		// Parse database name from URL
		String database = parseDatabase(url, info);

		// Set case insensitivity by default
		if (!info.containsKey("caseSensitive")) {
			info.setProperty("caseSensitive", "false");
		}

		// Get connection from parent (Calcite)
		Connection conn = super.connect(url, info);
		if (conn == null) {
			return null;
		}

		// Set up Convex schema if database specified
		if (database != null && conn instanceof CalciteConnection calciteConn) {
			ConvexSchema schema = ConvexSchemaFactory.createFromRegistry(database);
			if (schema != null) {
				SchemaPlus rootSchema = calciteConn.getRootSchema();
				rootSchema.add(database, schema);
				calciteConn.setSchema(database);
			} else {
				throw new SQLException("Database '" + database + "' not registered. " +
					"Call ConvexSchemaFactory.register() first.");
			}
		}

		return conn;
	}

	private String parseDatabase(String url, Properties info) {
		// Check properties first
		String database = info.getProperty("database");
		if (database != null) {
			return database;
		}

		// Parse from URL: jdbc:convex:database=mydb or jdbc:convex:mydb
		String params = url.substring(PREFIX.length());
		if (params.isEmpty()) {
			return null;
		}

		// Handle database=name format
		if (params.startsWith("database=")) {
			return params.substring(9).split(";")[0];
		}

		// Handle simple name format (jdbc:convex:mydb)
		if (!params.contains("=")) {
			return params.split(";")[0];
		}

		// Parse key=value pairs
		for (String part : params.split(";")) {
			if (part.startsWith("database=")) {
				return part.substring(9);
			}
		}

		return null;
	}
}
