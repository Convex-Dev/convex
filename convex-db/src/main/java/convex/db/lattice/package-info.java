/**
 * Lattice-based SQL table storage for Convex DB.
 *
 * <p>This package provides the core lattice implementation for SQL tables with
 * conflict-free merge replication. It is independent of the SQL parsing layer.
 *
 * <p>Key classes:
 * <ul>
 *   <li>{@link convex.db.lattice.SQLDatabase} - Database instance with signing and replication</li>
 *   <li>{@link convex.db.lattice.SQLSchema} - Table store: table operations and the per-table merge</li>
 *   <li>{@link convex.db.lattice.SQLTable} - Table entry (schema + rows) and its merge rule</li>
 *   <li>{@link convex.db.lattice.TableLattice} - Row index merge, used by the table rule</li>
 *   <li>{@link convex.db.lattice.SQLRow} - Row entry structure (values + timestamp)</li>
 * </ul>
 *
 * <p>Merges are executed by the components. Each has a pure {@code mergeValues} rule
 * that calls the rule of its child component for every key, from the database map in
 * {@link convex.db.ConvexDB} down to {@link convex.db.lattice.SQLTable}, which is the
 * merge boundary. Incoming merges, path delivery, fork publication and replica merges
 * all use these rules. An application changes them by extending a component and
 * supplying it from its parent's factory.
 *
 * <p>Standard merge semantics:
 * <ul>
 *   <li>Databases and tables merge as a union of names</li>
 *   <li>Tables use LWW on utime, the schema timestamp, and row-level merge for data</li>
 *   <li>Rows use LWW with timestamp; equal timestamps favour deletions</li>
 * </ul>
 *
 * @see <a href="https://docs.convex.world/cad/039_convex_sql">CAD039: Convex SQL</a>
 */
package convex.db.lattice;
