package convex.lattice.cursor;

/**
 * The one exclusive reservation of a transactional boundary, a {@link BoundaryCursor}
 * or a {@link Root}, held by the thread that took it. While it is held, a write
 * through the boundary from another thread that would change its value waits until
 * the claim is closed, and another claim on the boundary waits too. A root is the
 * outermost boundary, so every write to it passes through it.
 */
public interface Claim extends AutoCloseable {

	/** Releases the claim. Must be called by the thread that took it. */
	@Override void close();
}
