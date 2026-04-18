/**
 * Decentralised Lattice File System (DLFS) — a convergent, versioned
 * file system built on lattice primitives.
 *
 * <p>Exposes POSIX-style paths, directories, and files through Java NIO
 * ({@code FileSystem}, {@code Path}, {@code FileChannel}), backed by immutable
 * lattice nodes. DLFS supports multiple drives, efficient structural sharing,
 * and deterministic merges across distributed replicas.</p>
 */
package convex.lattice.fs;
