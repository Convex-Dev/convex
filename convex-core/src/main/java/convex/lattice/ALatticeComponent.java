package convex.lattice;

import java.io.IOException;
import java.util.Objects;

import convex.core.data.ACell;
import convex.lattice.cursor.ALatticeCursor;

/**
 * Domain operations over a lattice cursor, with resolved local configuration.
 *
 * <p>Components retain no parent or host component. State changes and sync travel
 * solely through their cursor. Construction from another component copies its
 * checkpoint policy; it does not retain that component.</p>
 *
 * @param <V> Value type of the wrapped cursor
 */
public abstract class ALatticeComponent<V extends ACell> {

	protected final ALatticeCursor<V> cursor;
	private volatile CheckpointPolicy checkpointPolicy;

	/** Creates a component; persistence, when available, is provided through its cursor. */
	protected ALatticeComponent(ALatticeCursor<V> cursor) {
		this.cursor=Objects.requireNonNull(cursor,"Component cursor must not be null");
		this.checkpointPolicy=CheckpointPolicy.INCREMENTAL;
	}

	/**
	 * Binds a child, fork or transaction view, copying configuration from source.
	 * Null source creates a standalone component. Later configuration changes to
	 * either component do not affect the other; no reference to source is retained.
	 */
	protected ALatticeComponent(ALatticeComponent<?> source,ALatticeCursor<V> cursor) {
		this(cursor);
		if (source!=null) checkpointPolicy=source.getCheckpointPolicy();
	}

	/** Returns the cursor responsible for all state updates and sync. */
	public ALatticeCursor<V> cursor() {
		return cursor;
	}

	/** Returns the resolved checkpoint policy; never null. */
	public final CheckpointPolicy getCheckpointPolicy() {
		return checkpointPolicy;
	}

	/**
	 * Configures this component and the default copied by subsequently constructed
	 * views. Existing views retain their policy. Configure before constructing
	 * domain components. Explicit persistence and sync are unaffected.
	 */
	public final void setCheckpointPolicy(CheckpointPolicy policy) {
		checkpointPolicy=Objects.requireNonNull(policy,"Checkpoint policy must not be null");
	}

	/**
	 * Optionally persists a working value without moving a cursor or publishing it.
	 * Callers install the returned value to benefit from store-backed references.
	 * Unhosted components retain working values in memory. Specialised implementations
	 * may override this hook and persist(value) for independently owned storage.
	 */
	protected <T extends ACell> T checkpoint(T value) throws IOException {
		return (checkpointPolicy==CheckpointPolicy.DEFERRED)?value:cursor.checkpoint(value);
	}

	/** Persists through the cursor's host capability without updating or syncing state. */
	protected <T extends ACell> T persist(T value) throws IOException {
		return cursor.persist(value);
	}

	/**
	 * Persists this component's current value without changing its cursor.
	 *
	 * @return Persisted current value
	 * @throws IOException If persistence fails
	 */
	public V persist() throws IOException {
		return persist(cursor.get());
	}

	/**
	 * Synchronises this component's cursor through its immediate cursor boundary.
	 *
	 * <p>For a forked cursor this merges local changes into the cursor it was forked
	 * from; it does not necessarily publish the complete application root. Call
	 * the containing {@link ALatticeApplication application's} {@code sync()} when
	 * the merged state should cross the host publication boundary.</p>
	 *
	 * <p>A sync invoked on an application or another live path reaches the hosted
	 * root and runs its synchronous persistence and publication policy. Physical
	 * durability remains the separate application {@code flush()} operation.</p>
	 */
	public void sync() {
		cursor.sync();
	}
}
