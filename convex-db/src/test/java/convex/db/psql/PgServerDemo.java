package convex.db.psql;

import convex.core.crypto.AKeyPair;
import convex.db.calcite.ConvexColumnType;
import convex.db.calcite.ConvexSchemaFactory;
import convex.db.calcite.ConvexType;
import convex.db.lattice.SQLDatabase;

/**
 * Demo server for testing PostgreSQL wire protocol with external clients.
 *
 * <p>Run this class to start a PostgreSQL-compatible server on port 5432.
 * Connect with any PostgreSQL client:
 * <pre>
 * psql -h localhost -p 5432 -d demo
 * </pre>
 *
 * <p>Or use a GUI client like BeeKeeper Studio, DBeaver, etc.
 */
public class PgServerDemo {

	public static void main(String[] args) throws Exception {
		int port = 5432;
		String dbName = "demo";

		// Parse command line arguments
		for (int i = 0; i < args.length; i++) {
			switch (args[i]) {
				case "-p", "--port" -> port = Integer.parseInt(args[++i]);
				case "-d", "--database" -> dbName = args[++i];
				case "-h", "--help" -> {
					System.out.println("Usage: PgServerDemo [-p port] [-d database]");
					System.out.println("  -p, --port      Port to listen on (default: 5432)");
					System.out.println("  -d, --database  Database name (default: demo)");
					return;
				}
			}
		}

		// Create and register a demo database
		System.out.println("Creating demo database: " + dbName);
		AKeyPair kp = AKeyPair.generate();
		SQLDatabase db = SQLDatabase.create(dbName, kp);
		ConvexSchemaFactory.setDatabase(db);

		// Create sample tables
		createSampleData(db);

		// Start server
		PgServer server = PgServer.builder()
			.port(port)
			.database(dbName)
			.build();

		// Add shutdown hook
		Runtime.getRuntime().addShutdownHook(new Thread(() -> {
			System.out.println("\nShutting down...");
			server.stop();
		}));

		System.out.println("Starting PostgreSQL server on port " + port);
		System.out.println("Database: " + dbName);
		System.out.println();
		System.out.println("Connect with:");
		System.out.println("  psql -h localhost -p " + port + " -d " + dbName);
		System.out.println();
		System.out.println("Sample queries:");
		System.out.println("  SELECT * FROM users;");
		System.out.println("  SELECT * FROM products;");
		System.out.println("  SELECT u.name, o.total FROM users u JOIN orders o ON u.id = o.user_id;");
		System.out.println();
		System.out.println("Press Ctrl+C to stop the server.");
		System.out.println();

		server.startAndWait();
	}

	private static void createSampleData(SQLDatabase db) {
		var tables = db.tables();

		// Users table
		ConvexColumnType[] userTypes = {
			ConvexColumnType.of(ConvexType.INTEGER),   // id
			ConvexColumnType.varchar(50),              // name
			ConvexColumnType.varchar(100),             // email
			ConvexColumnType.varchar(50)               // city
		};
		tables.createTable("users", new String[]{"id", "name", "email", "city"}, userTypes);
		tables.insert("users", 1L, "Alice", "alice@example.com", "London");
		tables.insert("users", 2L, "Bob", "bob@example.com", "Paris");
		tables.insert("users", 3L, "Carol", "carol@example.com", "London");
		tables.insert("users", 4L, "Dave", "dave@example.com", "Berlin");
		tables.insert("users", 5L, "Eve", "eve@example.com", "Paris");

		// Products table
		ConvexColumnType[] productTypes = {
			ConvexColumnType.of(ConvexType.INTEGER),   // id
			ConvexColumnType.varchar(100),             // name
			ConvexColumnType.of(ConvexType.DOUBLE),    // price
			ConvexColumnType.of(ConvexType.INTEGER)    // stock
		};
		tables.createTable("products", new String[]{"id", "name", "price", "stock"}, productTypes);
		tables.insert("products", 1L, "Widget", 9.99, 100L);
		tables.insert("products", 2L, "Gadget", 24.99, 50L);
		tables.insert("products", 3L, "Gizmo", 14.99, 75L);
		tables.insert("products", 4L, "Thingamajig", 49.99, 25L);

		// Orders table
		ConvexColumnType[] orderTypes = {
			ConvexColumnType.of(ConvexType.INTEGER),   // id
			ConvexColumnType.of(ConvexType.INTEGER),   // user_id
			ConvexColumnType.of(ConvexType.DOUBLE),    // total
			ConvexColumnType.varchar(20)               // status
		};
		tables.createTable("orders", new String[]{"id", "user_id", "total", "status"}, orderTypes);
		tables.insert("orders", 101L, 1L, 34.98, "completed");
		tables.insert("orders", 102L, 1L, 49.99, "pending");
		tables.insert("orders", 103L, 2L, 24.99, "completed");
		tables.insert("orders", 104L, 3L, 89.97, "completed");
		tables.insert("orders", 105L, 4L, 9.99, "shipped");

		System.out.println("Created sample tables: users, products, orders");
	}
}
