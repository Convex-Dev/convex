/**
 * Apache Calcite adapter for Convex lattice tables.
 *
 * <p>This package provides SQL query and DDL support for Convex lattice data
 * by implementing Calcite's adapter interfaces:
 *
 * <ul>
 *   <li>{@link ConvexSchemaFactory} - Creates schema instances from model config
 *   <li>{@link ConvexSchema} - Provides table map and DDL operations
 *   <li>{@link ConvexTable} - Scannable and modifiable table backed by lattice
 *   <li>{@link ConvexEnumerator} - Row iterator for query execution
 * </ul>
 *
 * <p>Usage via model file:
 * <pre>
 * {
 *   "version": "1.0",
 *   "defaultSchema": "convex",
 *   "schemas": [{
 *     "name": "convex",
 *     "type": "custom",
 *     "factory": "convex.db.calcite.ConvexSchemaFactory",
 *     "operand": {
 *       "database": "mydb"
 *     }
 *   }]
 * }
 * </pre>
 *
 * <p>Or programmatically via {@link convex.db.sql.SQLEngine}.
 */
package convex.db.calcite;
