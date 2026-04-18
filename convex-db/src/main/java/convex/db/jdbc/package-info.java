/**
 * JDBC driver for Convex SQL databases.
 *
 * <p>Provides standard JDBC connectivity using the {@code jdbc:convex:} URL scheme.
 *
 * <p>Usage:
 * <pre>
 * // 1. Create and register database
 * ConvexDB cdb = ConvexDB.create();
 * cdb.database("mydb");
 * cdb.register("mydb");
 *
 * // 2. Connect via standard JDBC (driver auto-registers)
 * Connection conn = DriverManager.getConnection("jdbc:convex:database=mydb");
 *
 * // 3. Use standard JDBC
 * Statement stmt = conn.createStatement();
 * ResultSet rs = stmt.executeQuery("SELECT * FROM users");
 * </pre>
 *
 * @see convex.db.jdbc.ConvexDriver
 * @see convex.db.ConvexDB#register
 */
package convex.db.jdbc;
