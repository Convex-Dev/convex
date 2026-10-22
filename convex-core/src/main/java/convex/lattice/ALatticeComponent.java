package convex.lattice;

import java.util.Arrays;
import java.util.Objects;

import convex.core.data.ACell;
import convex.core.data.AString;
import convex.lattice.cursor.ACursor;
import convex.lattice.cursor.AForkableCursor;
import convex.lattice.cursor.ForkedCursor;

/**
 * Domain operations over a cursor at one path.
 *
 * <p>Lightweight components share a working cursor. Components with an independent
 * publication boundary own a {@link ForkedCursor} of their destination, whose sync
 * reconciles through this component's publication merge. Representation changes
 * compose through destination cursor views, such as signing or projection.
 * Publication uses ordinary cursor updates; there is no component parent link.
 * The cursor knows nothing about lattices: the component declares the structure of
 * the level it owns, supplying read/write functions for child views and binding
 * the authoring functions of signing and stamping views.
 * Merge and publication policy remain in components: {@link #merge} is a component's
 * own behaviour for an incoming value at its level, delegating to a lattice or not.</p>
 *
 * <p>No abstract lattice definition is required. Domain components may call lattice
 * rules as implementation details. {@link LatticeComponent} adapts a supplied lattice
 * when its generic merge and navigation are the intended component behaviour.</p>
 *
 * <p>Persistence belongs to the host ({@link LatticeHost}) and to components which
 * explicitly own a store for their own data, such as DLFS drives.</p>
 *
 * @param <V> Value type of the wrapped cursor
 */
public abstract class ALatticeComponent<V extends ACell> {

	private final AForkableCursor<V> cursor;
	/** Working copy this component owns and publishes, or null for a lightweight view. */
	private final ForkedCursor<V> owned;

	/**
	 * Shares supplied working storage without taking ownership of its publication.
	 * Default sync is a read-only no-op, even when the supplied cursor is a fork.
	 */
	protected ALatticeComponent(AForkableCursor<V> cursor) {
		this.cursor=Objects.requireNonNull(cursor,"Component cursor must not be null");
		owned=null;
	}

	/**
	 * Creates a component with private working state and a publication destination.
	 * Children normally share paths in this working state; they need no forks.
	 * Sync publishes one snapshot into the destination without syncing that
	 * destination further. Use {@code super(forkOf(destination))} to select this
	 * boundary explicitly, regardless of the destination cursor's Java type.
	 */
	protected ALatticeComponent(ForkTarget<V> target) {
		owned=ForkedCursor.create(target.destination(),this::mergePublication);
		cursor=owned;
	}

	/** Declares a destination for a new component-owned fork; does not create or publish state. */
	protected static <T extends ACell> ForkTarget<T> forkOf(ACursor<T> destination) {
		return new ForkTarget<>(Objects.requireNonNull(destination,"Fork destination must not be null"));
	}

	/** Explicit construction choice, distinct from sharing a supplied cursor. */
	protected record ForkTarget<T extends ACell>(ACursor<T> destination) {}

	/** Creates independent working state over another component. Subclasses retain their own policies. */
	protected ALatticeComponent(ALatticeComponent<V> source) {
		this(forkOf(source.cursor()));
	}

	/**
	 * Reconciles a working snapshot with a concurrently changed destination.
	 * Called only when both sides changed, within the destination's atomic update.
	 * Implementations must use the supplied values without mutating working state.
	 * Staged components must define this operation; live views do not need it.
	 */
	protected V mergePublication(V own,V other) {
		throw new UnsupportedOperationException(getClass().getSimpleName()+" defines no publication merge");
	}

	/**
	 * Returns the atomic editing view. Domain operations and children use this
	 * accessor so an override can consistently apply policies such as timestamping.
	 * A subclass can wrap {@code super.cursor()} during construction; constructors
	 * must not invoke an override before its view is initialised. Updating this
	 * cursor does not publish the component.
	 */
	public AForkableCursor<V> cursor() {
		return cursor;
	}

	/**
	 * Checks whether a candidate value is compatible with this component.
	 * Validity is application-defined; success need not imply merge acceptance.
	 * For example, merge may additionally enforce ownership, freshness or limits.
	 * The default declares no constraints and accepts every value, including nil.
	 *
	 * <p>The candidate uses this component's delivery representation, which may
	 * differ from its working cursor: signed components check complete envelopes.
	 * Implementations must inspect the supplied value without installing it or
	 * changing component state. Ordinary incompatibility, including a wrong value
	 * type, should return an explanation rather than throw.</p>
	 *
	 * <p>This is an explicit diagnostic, not an automatic lifecycle guard. Reads,
	 * local edits and sync do not invoke it. Local operations should preserve their
	 * invariants by construction; merge must enforce its own acceptance rules even
	 * when this method has not been called. A merge may reuse checks where useful,
	 * normally only for changed incoming data rather than established state.</p>
	 *
	 * @param value Candidate value, possibly nil or of an unexpected type
	 * @return Null if compatible, otherwise an application-defined error reason
	 */
	public AString checkValue(ACell value) {
		return null;
	}

	/**
	 * Merges an incoming value at this component's level into its working state. This
	 * is the component's own behaviour: a lattice offers a default merge for the shape
	 * it declares, and the component delegates to it, in whole or in part, or applies
	 * another rule. A component that receives nothing need not define one; the base
	 * refuses, so a delivery routed to it fails rather than vanishing.
	 *
	 * @param other Incoming value at this level
	 * @return Current working value
	 */
	public V merge(V other) {
		throw new UnsupportedOperationException(getClass().getSimpleName()+" defines no merge for incoming values");
	}

	/**
	 * Resolves this component's child component for one key below its level, or null
	 * when it has none there. Unresolved paths go to {@link #mergeAtPath}.
	 * A component with children overrides this to hand a delivery on to them.
	 *
	 * @param <T> Value type at the key
	 * @param key Key below this level
	 * @return Child component, or null
	 */
	protected <T extends ACell> ALatticeComponent<T> child(ACell key) {
		return null;
	}

	/**
	 * Delivers a complete value here, or routes a descendant delivery to a child.
	 * Unresolved paths go to {@link #mergeAtPath}; no value shape is inferred.
	 * Returns current working state, not a publication acknowledgement.
	 */
	@SuppressWarnings("unchecked")
	public V mergeAt(ACell value, ACell... path) {
		if (path.length==0) return merge((V)value);
		ALatticeComponent<ACell> child=child(path[0]);
		if (child!=null) {
			child.mergeAt(value,Arrays.copyOfRange(path,1,path.length));
			return cursor().get();
		}
		return mergeAtPath(value,path);
	}

	/** Handles a descendant path not claimed by a child. The default rejects unsupported routing. */
	protected V mergeAtPath(ACell value,ACell... path) {
		throw new UnsupportedOperationException(getClass().getSimpleName()+" defines no delivery for this path");
	}

	/**
	 * Publishes one working snapshot into this component's destination using
	 * CAS-or-merge. Publication stops at that cursor: its sync is never invoked.
	 * A lightweight view has no independent boundary and simply returns its value;
	 * its edits are already visible in the containing working state.
	 * This default does not write, sign, stamp or create absent state for a view.
	 *
	 * <p>Call the enclosing application's sync to run its preparation and publication,
	 * and the host's sync for persistence or replication. A caller-owned cursor
	 * supplied to a view, forked or not, is never published by the component.</p>
	 *
	 * @return Published snapshot, or current value for a lightweight view. Later
	 *         concurrent edits may remain pending in working storage.
	 */
	public V sync() {
		return owned==null?cursor().get():owned.sync();
	}

}
