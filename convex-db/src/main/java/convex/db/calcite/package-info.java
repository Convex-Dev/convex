/**
 * Apache Calcite adapter for Convex lattice tables.
 *
 * <p>Implements a custom Calcite convention ({@code CONVEX}) that keeps
 * CVM types ({@code ACell[]}) throughout the query execution pipeline,
 * converting to Java types only at the JDBC boundary.
 *
 * <p>Key classes:
 * <ul>
 *   <li>{@link ConvexTable} — {@code TranslatableTable} that returns
 *       {@code ConvexTableScan} directly via {@code toRel()}, bypassing
 *       Calcite's {@code EnumerableTableScan}
 *   <li>{@link ConvexSchema} — provides the table map for a database
 *   <li>{@link ConvexSchemaFactory} — creates schema instances from model config
 *   <li>{@link ConvexType} — SQL ↔ CVM type mapping
 * </ul>
 *
 * <p>The convention pipeline ({@code ConvexTableScan → ConvexFilter →
 * ConvexSort → ...}) operates on {@code ACell[]} rows with CVM-native
 * comparisons and arithmetic. {@code ConvexToEnumerableConverter} bridges
 * to Calcite's Enumerable layer at the plan boundary.
 *
 * <p>Primary key equality filters ({@code WHERE id = ?}) are pushed down
 * to the radix tree index for O(log n) point lookups.
 *
 * @see convex.db.jdbc.ConvexDriver
 * @see convex.db.calcite.convention.ConvexRel
 */
package convex.db.calcite;
