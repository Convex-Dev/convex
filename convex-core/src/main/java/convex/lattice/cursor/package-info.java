/**
 * Cursor-based navigation and transformation over lattice structures.
 *
 * <p>A {@link convex.lattice.cursor.ACursor} is an atomic view into a lattice
 * value, supporting path-based traversal, cached reads, signed access envelopes
 * and transformation pipelines. Cursors are the primary way higher-level code
 * reads and writes values within a lattice without materialising the whole tree.
 * A fork takes its merge function when it is created; publication policy belongs
 * to components, which own the forks they publish through.</p>
 *
 * <p>All cross-lattice path walking should go through this package rather
 * than reinventing traversal logic.</p>
 */
package convex.lattice.cursor;