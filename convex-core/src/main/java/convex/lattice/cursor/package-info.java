/**
 * Cursor-based navigation and transformation over lattice structures.
 *
 * <p>A {@link convex.lattice.cursor.ACursor} is an atomic view into a lattice
 * value, supporting path-based traversal, cached reads, signed access envelopes
 * and transformation pipelines. Cursors are the primary way higher-level code
 * reads and writes values within a lattice without materialising the whole tree.
 * They neither fork nor sync: working copies and publication belong to
 * components.</p>
 *
 * <p>All cross-lattice path walking should go through this package rather
 * than reinventing traversal logic.</p>
 */
package convex.lattice.cursor;