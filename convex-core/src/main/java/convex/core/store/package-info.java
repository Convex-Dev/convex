/**
 * Pluggable storage abstraction for immutable CVM cells.
 *
 * <p>Defines {@link convex.core.store.AStore} — the content-addressed, hash-indexed
 * store contract used throughout the CVM — with in-memory, null, and cached
 * implementations in this package. Persistent disk-backed storage is provided
 * by the {@link convex.etch Etch} engine.</p>
 */
package convex.core.store;