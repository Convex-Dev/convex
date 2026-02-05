package convex.db.sql;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;

import convex.core.crypto.AKeyPair;
import convex.db.calcite.ConvexSchemaFactory;
import convex.db.lattice.SQLDatabase;

/**
 * Demo showing Convex SQL database usage via standard JDBC.
 */
public class SQLDemo {

	public static void main(String[] args) throws Exception {
		System.out.println("=== Convex SQL Demo ===\n");

		// 1. Create and register database
		AKeyPair kp = AKeyPair.generate();
		SQLDatabase db = SQLDatabase.create("demo", kp);
		ConvexSchemaFactory.register("demo", db);
		System.out.println("Database: " + db.getName());

		// 2. Create table via database API
		db.tables().createTable("employees", new String[]{"id", "name", "dept", "salary"});
		System.out.println("Created table: employees");

		// 3. Connect via JDBC and use standard SQL
		try (Connection conn = DriverManager.getConnection("jdbc:convex:database=demo")) {
			System.out.println("Connected via jdbc:convex:\n");

			// Insert rows via JDBC
			try (Statement stmt = conn.createStatement()) {
				stmt.executeUpdate("INSERT INTO employees VALUES (1, 'Alice', 'Engineering', 95000)");
				stmt.executeUpdate("INSERT INTO employees VALUES (2, 'Bob', 'Sales', 75000)");
				stmt.executeUpdate("INSERT INTO employees VALUES (3, 'Charlie', 'Engineering', 105000)");
				stmt.executeUpdate("INSERT INTO employees VALUES (4, 'Diana', 'Marketing', 80000)");
				stmt.executeUpdate("INSERT INTO employees VALUES (5, 'Eve', 'Engineering', 90000)");
			}
			System.out.println("Inserted 5 rows via JDBC\n");

			// Query via JDBC
			System.out.println("--- SELECT * FROM employees ---");
			query(conn, "SELECT * FROM employees");

			System.out.println("\n--- WHERE dept = 'Engineering' ---");
			query(conn, "SELECT name, salary FROM employees WHERE dept = 'Engineering'");

			System.out.println("\n--- ORDER BY salary DESC ---");
			query(conn, "SELECT name, salary FROM employees ORDER BY salary DESC");

			System.out.println("\n--- COUNT(*) ---");
			query(conn, "SELECT COUNT(*) as total FROM employees");
		}
	}

	private static void query(Connection conn, String sql) throws Exception {
		try (Statement stmt = conn.createStatement();
			 ResultSet rs = stmt.executeQuery(sql)) {
			ResultSetMetaData meta = rs.getMetaData();
			int cols = meta.getColumnCount();

			for (int i = 1; i <= cols; i++) System.out.print(pad(meta.getColumnLabel(i)));
			System.out.println();

			while (rs.next()) {
				for (int i = 1; i <= cols; i++) {
					Object v = rs.getObject(i);
					if (v instanceof byte[] b) v = "0x" + hex(b);
					System.out.print(pad(v));
				}
				System.out.println();
			}
		}
	}

	private static String pad(Object v) { return String.format("%-20s", v); }
	private static String hex(byte[] b) {
		if (b.length <= 8) {
			StringBuilder sb = new StringBuilder();
			for (byte x : b) sb.append(String.format("%02x", x));
			return sb.toString();
		}
		return String.format("%02x%02x...(%d)", b[0], b[1], b.length);
	}
}
