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

		// 2. Create table
		db.tables().createTable("employees", new String[]{"id", "name", "dept", "salary"});

		// 3. Insert rows (Java types auto-convert)
		db.tables().insert("employees", 1, 1, "Alice", "Engineering", 95000);
		db.tables().insert("employees", 2, 2, "Bob", "Sales", 75000);
		db.tables().insert("employees", 3, 3, "Charlie", "Engineering", 105000);
		db.tables().insert("employees", 4, 4, "Diana", "Marketing", 80000);
		db.tables().insert("employees", 5, 5, "Eve", "Engineering", 90000);

		// 4. Query via JDBC
		try (SQLEngine engine = SQLEngine.create(db)) {
			Connection conn = engine.getConnection();

			System.out.println("\n--- SELECT * FROM employees ---");
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

			// Header
			for (int i = 1; i <= cols; i++) System.out.print(pad(meta.getColumnLabel(i)));
			System.out.println();

			// Rows
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

	private static String pad(Object v) { return String.format("%-15s", v); }
	private static String hex(byte[] b) {
		if (b.length <= 4) {
			StringBuilder sb = new StringBuilder();
			for (byte x : b) sb.append(String.format("%02x", x));
			return sb.toString();
		}
		return String.format("%02x%02x...(%d)", b[0], b[1], b.length);
	}
}
