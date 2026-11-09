package convex.lattice;

import convex.core.data.ACell;
import convex.lattice.cursor.AForkableCursor;
import convex.lattice.cursor.Persister;

/**
 * Application lifecycle over a component's working state and managed children.
 *
 * <p>Applications define incoming merge and routing through their domain components.
 * No abstract lattice or parallel structure declaration is required. An application
 * binds the storage it is given: a live application shares {@code host.cursor()},
 * and one holding a private copy of the root is given a fork with the function that
 * persists it, {@code super(host.cursor().fork(), host::persist)}.
 * Children may share that state or own separate publication boundaries. Which
 * children are given such a function is the application's policy.</p>
 *
 * <p>Sync prepares the application, syncs its managed children, then publishes its
 * own working boundary. Publication stops at the destination cursor. The host owns
 * storage and replication; host sync and physical durability remain separate.
 * Failures stop later stages without rolling back completed child publications.</p>
 *
 * @param <V> Value managed by the application
 */
public abstract class ALatticeApplication<V extends ACell> extends ALatticeComponent<V> {

	/** Shares working storage, for live applications, nested containers and transaction views. */
	protected ALatticeApplication(AForkableCursor<V> cursor) {
		super(cursor);
	}

	/**
	 * Binds working state of its own with the function that persists it, normally a
	 * private copy of the root, {@code host.cursor().fork()}, with {@code host::persist}.
	 */
	protected ALatticeApplication(AForkableCursor<V> cursor,Persister<V> persister) {
		super(cursor,persister);
	}

	/**
	 * Prepares application state before children or the application publish.
	 * Failure prevents subsequent publication stages. This hook can be called
	 * concurrently and must use thread-safe component APIs. Individual component
	 * syncs do not invoke the containing application's lifecycle.
	 */
	protected void beforeSync() {
	}

	/**
	 * Publishes managed children after preparation and before this application's
	 * own boundary. The default has none. Implementations define child order;
	 * completed child publications stand if a later child fails.
	 */
	protected void syncChildren() {
	}

	/** Prepares, publishes managed children, then publishes this application's own boundary. */
	@Override
	public V sync() {
		beforeSync();
		syncChildren();
		return super.sync();
	}

	/** Accepts incoming state according to this application's domain policy, without publishing it. */
	@Override
	public abstract V merge(V incoming);
}
