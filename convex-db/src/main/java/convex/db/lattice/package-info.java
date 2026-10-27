/**
 * Lattice-based SQL table storage for Convex DB.
 *
 * <p>This package provides the lattice storage for SQL tables. It is independent of
 * the SQL parsing layer.
 *
 * <p>Key classes:
 * <ul>
 *   <li>{@link convex.db.lattice.SQLDatabase} - Database instance with signing, replication and its merge policy</li>
 *   <li>{@link convex.db.lattice.SQLSchema} - Table store: table operations</li>
 *   <li>{@link convex.db.lattice.SQLTable} - Table entry (schema + rows)</li>
 *   <li>{@link convex.db.lattice.SQLRow} - Row entry structure (values + timestamp)</li>
 *   <li>{@link convex.db.lattice.RowLWW} - The per-row last-write-wins merge policy</li>
 * </ul>
 *
 * <p>How a database merges is a policy supplied by whatever constructs it, with no
 * default. The database is the unit of merging: incoming merges, path delivery, fork
 * publication and replica merges all go through its policy, and the table store and
 * tables are live views with no merge of their own. {@link convex.db.ConvexDB} merges
 * its databases by name, each with that policy.
 *
 * @see <a href="https://docs.convex.world/docs/cad/convex_sql">CAD039: Convex SQL</a>
 */
package convex.db.lattice;
