package convex.db.sql;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;

import convex.core.crypto.AKeyPair;
import convex.db.lattice.SQLDatabase;

/**
 * Demo showing Convex SQL database usage via JDBC.
 */
public class SQLDemo {

	public static void main(String[] args) throws Exception {
		System.out.println("=== Convex SQL Demo ===\n");

		// 1. Create database
		AKeyPair kp = AKeyPair.generate();
		SQLDatabase db = SQLDatabase.create("demo", kp);
		System.out.println("Database: " + db.getName());

		// 2. Create table via schema API, insert via SQL
		try (SQLEngine engine = SQLEngine.create(db)) {
			// Create table via schema API
			engine.getSchema().createTable("employees", "id", "name", "dept", "salary");
			System.out.println("Created table via schema API");

			// Insert rows via SQL (Calcite handles parsing)
			engine.execute("INSERT INTO employees VALUES (1, 'Alice', 'Engineering', 95000)");
			engine.execute("INSERT INTO employees VALUES (2, 'Bob', 'Sales', 75000)");
			engine.execute("INSERT INTO employees VALUES (3, 'Charlie', 'Engineering', 105000)");
			engine.execute("INSERT INTO employees VALUES (4, 'Diana', 'Marketing', 80000)");
			engine.execute("INSERT INTO employees VALUES (5, 'Eve', 'Engineering', 90000)");
			System.out.println("Inserted 5 rows via SQL\n");
		}

		// 3. Query via separate JDBC connection
		try (SQLEngine reader = SQLEngine.create(db)) {
			Connection conn = reader.getConnection();

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
