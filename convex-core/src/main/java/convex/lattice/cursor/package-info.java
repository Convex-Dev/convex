/**
 * Cursor-based navigation and transformation over lattice structures.
 *
 * <p>A {@link convex.lattice.cursor.ACursor} is a movable, forkable view into
 * a lattice, supporting path-based traversal, cached reads, signed access
 * envelopes, and transformation pipelines. Cursors are the primary way higher-
 * level code reads, writes, and subscribes to values within a lattice without
 * materialising the whole tree.</p>
 *
 * <p>All cross-lattice path walking should go through this package rather
 * than reinventing traversal logic.</p>
 */
package convex.lattice.cursor;