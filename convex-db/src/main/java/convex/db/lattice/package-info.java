/**
 * Lattice-based SQL table storage for Convex DB.
 *
 * <p>This package provides the core lattice implementation for SQL tables with
 * conflict-free merge replication. It is independent of the SQL parsing layer.
 *
 * <p>Key classes:
 * <ul>
 *   <li>{@link convex.db.lattice.SQLDatabase} - Database instance with signing and replication</li>
 *   <li>{@link convex.db.lattice.SQLTables} - Facade for table operations</li>
 *   <li>{@link convex.db.lattice.TableStoreLattice} - Lattice for database's table store</li>
 *   <li>{@link convex.db.lattice.TableLattice} - Lattice for individual table rows</li>
 *   <li>{@link convex.db.lattice.SQLTable} - Table entry structure (schema + rows)</li>
 *   <li>{@link convex.db.lattice.SQLRow} - Row entry structure (values + timestamp)</li>
 * </ul>
 *
 * <p>Merge semantics:
 * <ul>
 *   <li>Tables use LWW for schema, row-level merge for data</li>
 *   <li>Rows use LWW with timestamp; equal timestamps favour deletions</li>
 * </ul>
 *
 * @see <a href="https://docs.convex.world/cad/039_convex_sql">CAD039: Convex SQL</a>
 */
package convex.db.lattice;
