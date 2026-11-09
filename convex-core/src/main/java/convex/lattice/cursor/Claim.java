package convex.lattice.cursor;

/**
 * A root's one exclusive reservation, held by the thread that took it. While it is
 * held, no other thread can change the part of the root's value it covers: a write
 * that would change that part waits until the claim is closed, and a write anywhere
 * else carries on. Other claims on the same root wait whatever they cover.
 */
public interface Claim extends AutoCloseable {

	/** Releases the claim. Must be called by the thread that took it. */
	@Override void close();
}
