package convex.lattice;

import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;

import convex.core.data.ACell;
import convex.lattice.cursor.AForkableCursor;
import convex.lattice.cursor.ThreeWayMerge;
import convex.lattice.cursor.Transactions;

/**
 * Exclusive transactions on a component's live state, made by
 * {@link TransactionalComponent#exclusive()}. Each callback runs once, on a private
 * draft, under the claim beneath this component's storage, and commits before the
 * claim is released. Use it for operations too expensive to repeat, or with side
 * effects.
 *
 * <p>Like a fork, this is built from the component's bound storage. A fork derives
 * private working storage and rebinds the domain component to it; this derives a
 * read-only view, and rebinds the domain component to a fresh draft for each
 * transaction. Reads see the source's current bound value, before any editing
 * policies. Writes and sync through this component throw
 * {@link convex.lattice.cursor.ReadOnlyCursorException}, and merges, forks,
 * persistence and non-empty paths are refused as the base component refuses them:
 * a transaction is its only way to change state.
 * Commits go to the source's bound storage, which this component holds privately
 * because the read-only view deliberately carries no write access.</p>
 *
 * <p>The claim is that of the nearest transactional boundary beneath the source's
 * bound storage ({@link convex.lattice.cursor.ACursor#claim}): a
 * {@link convex.lattice.cursor.BoundaryCursor}, or else the root, which is the
 * outermost boundary. It covers everything under that boundary: writes through it from
 * other threads wait, including incoming merges, and exclusive transactions under the
 * same boundary run one after another whatever they touch, while those under separate
 * boundaries run at the same time. A write reaching the value without passing
 * through the boundary, or made by the claiming thread outside the draft, is
 * reconciled with the callback's run by the source component's
 * {@link ALatticeComponent#reconcile(ACell,ACell,ACell)}, as {@link TransactionalComponent}
 * describes, or makes the transaction fail if it cannot reconcile changes on both sides. For a
 * staged component the root is that component's working copy, so a sync of the fork
 * from another thread waits for a running transaction.
 * A transaction on a staged component commits only to its fork; publishing remains a
 * separate sync, and a sync from the callback publishes the fork's working value,
 * without this transaction's draft.</p>
 *
 * <p>Callbacks must not wait for another thread's operation on the same state. A write
 * a callback makes outside its draft, through a captured component or by a fork's sync,
 * lands at once and stays if the callback or the commit fails. Such a write waits for any
 * claim it passes that another thread holds, so the callback's thread must hold each
 * such claim, taken before this one: a fork's claim before its destination's, a
 * region's before its container's. A thread may take a claim it already holds.</p>
 *
 * @param <V> Lattice value type
 * @param <C> Domain component type supplied to callbacks
 */
public final class ExclusiveComponent<V extends ACell,C> extends ALatticeComponent<V> {

	/** Bound storage of the source component, where commits go. */
	private final AForkableCursor<V> target;

	/** Binds the source's domain operations to each draft. */
	private final Function<? super AForkableCursor<V>,? extends C> binder;

	/** Reconciles a change under the claim made outside the draft, or null to fail instead. */
	private final ThreeWayMerge<V> merge;

	ExclusiveComponent(AForkableCursor<V> target,Function<? super AForkableCursor<V>,? extends C> binder,ThreeWayMerge<V> merge) {
		super(target.readOnly());
		this.target=target;
		this.binder=Objects.requireNonNull(binder);
		this.merge=merge;
	}

	/**
	 * Runs domain operations once on a private view under the claim beneath its storage,
	 * commits the transaction value after any reconciliation, and returns the
	 * callback's result.
	 * Reconciliation does not recompute that result or guarantee that the transaction's
	 * changes survive; see {@link TransactionalComponent}.
	 *
	 * @param <R> Result type
	 * @param action Domain operations on a private view, returning their result
	 * @return Result of the callback, computed before any merge at commit
	 */
	public <R> R transact(Function<? super C,? extends R> action) {
		Objects.requireNonNull(action);
		return Transactions.transactExclusively(target,draft -> {
			C view=binder.apply(draft);
			R result=action.apply(view);
			completeDraft(view);
			return result;
		},merge);
	}

	/**
	 * Runs domain operations once with no result, as {@link #transact}.
	 *
	 * @param action Domain operations on a private view
	 */
	public void execute(Consumer<? super C> action) {
		Objects.requireNonNull(action);
		Transactions.executeExclusively(target,draft -> {
			C view=binder.apply(draft);
			action.accept(view);
			completeDraft(view);
		},merge);
	}
}
