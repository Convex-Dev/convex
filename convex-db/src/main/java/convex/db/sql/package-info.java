/**
 * Apache Calcite SQL facade for Convex DB.
 *
 * <p>This package provides SQL query capabilities over lattice tables using
 * Apache Calcite for SQL parsing, planning, and execution.
 *
 * <p>Key classes:
 * <ul>
 *   <li>{@link convex.db.sql.SQLEngine} - High-level SQL execution API</li>
 *   <li>{@link convex.db.sql.ConvexSchema} - Calcite Schema exposing tables</li>
 *   <li>{@link convex.db.sql.ConvexTable} - Calcite Table for table scans</li>
 * </ul>
 *
 * <p>Usage:
 * <pre>
 * SQLDatabase db = SQLDatabase.create("mydb", keyPair);
 * SQLEngine engine = SQLEngine.create(db);
 * List&lt;Object[]&gt; results = engine.query("SELECT * FROM users");
 * </pre>
 *
 * @see <a href="https://docs.convex.world/cad/039_convex_sql">CAD039: Convex SQL</a>
 */
package convex.db.sql;
