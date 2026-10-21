package convex.lattice;

import java.util.Objects;

import convex.core.data.ACell;
import convex.lattice.cursor.AForkableCursor;

/**
 * Application lifecycle over a component's working state and managed children.
 *
 * <p>Applications define incoming merge and routing through their domain components.
 * No abstract lattice or parallel structure declaration is required. A host-bound
 * application owns a staged copy of the host value; a cursor-bound application
 * shares the supplied working state. Children may share that state or own separate
 * publication boundaries.</p>
 *
 * <p>Sync prepares the application, syncs its managed children, then publishes its
 * own working boundary. Publication stops at the destination cursor. The host owns
 * storage and replication; host sync and physical durability remain separate.
 * Failures stop later stages without rolling back completed child publications.</p>
 *
 * @param <V> Value managed by the application
 */
public abstract class ALatticeApplication<V extends ACell> extends ALatticeComponent<V> {

	/** Creates private working state over a host, retaining its current context. */
	protected ALatticeApplication(LatticeHost<V> host) {
		super(forkOf(Objects.requireNonNull(host,"Application host must not be null").cursor()));
		setContext(host.getContext());
	}

	/** Shares working storage, for live applications, nested containers and transaction views. */
	protected ALatticeApplication(AForkableCursor<V> cursor) {
		super(cursor);
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
