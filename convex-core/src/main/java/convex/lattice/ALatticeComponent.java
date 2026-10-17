package convex.lattice;

import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.Function;

import convex.core.data.ACell;
import convex.core.data.ADataStructure;
import convex.core.data.AMap;
import convex.core.data.SignedData;
import convex.core.lang.RT;
import convex.core.util.Utils;
import convex.lattice.cursor.ACursor;
import convex.lattice.cursor.AForkableCursor;
import convex.lattice.cursor.ForkedCursor;
import convex.lattice.cursor.SignedCursor;
import convex.lattice.cursor.ProjectionCursor;
import convex.lattice.generic.AKeyedLattice;
import convex.lattice.generic.AOwnerLattice;
import convex.lattice.generic.SignedLattice;

/**
 * Domain operations over a cursor at one path.
 *
 * <p>Lightweight components share a working cursor. Components with an independent
 * publication boundary own a {@link ForkedCursor} of their destination, whose sync
 * reconciles through functions this component supplies: its lattice merge under its
 * context, and for a type-changing destination its read and prepare functions.
 * Publication uses ordinary cursor updates; there is no component parent link.
 * The cursor knows nothing about lattices: the component declares the structure of
 * the level it owns, supplying read/write functions for child views and binding
 * the authoring functions of signing and stamping views.
 * Merge and publication policy remain in components: {@link #merge} is a component's
 * own behaviour for an incoming value at its level, delegating to a lattice or not.</p>
 *
 * <p>Persistence belongs to the host ({@link LatticeHost}) and to components which
 * explicitly own a store for their own data, such as DLFS drives.</p>
 *
 * @param <V> Value type of the wrapped cursor
 */
public abstract class ALatticeComponent<V extends ACell> {

	protected final AForkableCursor<V> cursor;
	private volatile LatticeContext context=LatticeContext.EMPTY;
	/** Working copy this component owns and publishes, or null for a lightweight view. */
	private final ForkedCursor<V> owned;

	/** Returns this component's write and merge policy. */
	public final LatticeContext getContext() { return context; }

	/** Replaces policy for this component. Existing children retain their resolved policy. */
	public final void setContext(LatticeContext context) {
		this.context=(context==null)?LatticeContext.EMPTY:context;
	}

	/** Resolves policy for a newly constructed child or rebound view, without retaining a parent. */
	protected final <C extends ALatticeComponent<?>> C configured(C child) {
		child.setContext(getContext());
		return child;
	}

	/** Creates independent working state with component-owned CAS-or-merge publication. */
	public static <T extends ACell> ALatticeComponent<T> working(ALattice<T> lattice,
			ACursor<T> destination, LatticeContext context) {
		ALatticeComponent<T> component=new ALatticeComponent<T>(lattice,destination) {};
		component.setContext(context);
		return component;
	}

	/** Immediate signing boundary with an explicitly bound authoring function. */
	protected final <T extends ACell> SignedCursor<T> signedCursor(
			ACursor<SignedData<T>> slot,
			AOwnerLattice<?,T,?> owners, ACell owner) {
		SignedLattice<T> rule=owners.signingFor(owner);
		if (rule==null) throw new IllegalArgumentException("Invalid owner: "+owner);
		LatticeContext policy=getContext();
		return SignedCursor.create(slot,v -> rule.sign(policy,v));
	}

	/** Creates a component over a cursor. */
	protected ALatticeComponent(AForkableCursor<V> cursor) {
		this.cursor=Objects.requireNonNull(cursor,"Component cursor must not be null");
		owned=null;
	}

	/**
	 * Creates a component with private working state and a publication destination.
	 * Children normally share paths in this working state; they need no forks.
	 * Sync publishes one snapshot into the destination without syncing that
	 * destination further. Write and merge policy belongs to this component. No workers or timers are created.
	 */
	protected ALatticeComponent(ALattice<V> lattice, ACursor<V> destination) {
		this(lattice,destination,Function.identity(),(context,value) -> value);
	}

	/**
	 * Creates a publication boundary whose destination has a different stored type.
	 * The fork receives closures over this component, resolved at publication time:
	 * {@code prepare} runs under the component's context then, and the merge goes
	 * through {@link #mergePublication}.
	 */
	protected <P extends ACell> ALatticeComponent(ALattice<V> lattice, ACursor<P> destination,
			Function<P,V> read, BiFunction<LatticeContext,V,P> prepare) {
		Objects.requireNonNull(lattice,"Publication lattice must not be null");
		Objects.requireNonNull(prepare,"Prepare function must not be null");
		owned=ForkedCursor.create(destination,read,
			value -> prepare.apply(getContext(),value),
			(own,other) -> mergePublication(lattice,own,other));
		cursor=owned;
	}

	/**
	 * Merges a publication snapshot with a destination that changed since the snapshot
	 * was last reconciled. The fork calls this only when both sides changed; an
	 * unchanged side is adopted by the fork itself. The local snapshot is own,
	 * including on timestamp ties.
	 *
	 * <p>A keyed lattice publishes only the keys it declares: every other key adopts
	 * the destination, so a component that understands some of a container's keys
	 * never overwrites or removes what another component owns there with the stale
	 * copy in its working state. This holds at any level, the host root included.
	 * Overrides must use only the supplied values and must not mutate live working
	 * cursors or publish.</p>
	 *
	 * <p>Runs inside the owned fork's sync, outside atomic update callbacks, once per
	 * distinct destination value. On retry, own is the previous prepared result, so
	 * unchanged signatures are reused.</p>
	 */
	@SuppressWarnings("unchecked")
	protected V mergePublication(ALattice<V> lattice,V own,V other) {
		V merged=lattice.merge(getContext(),own,other);
		if (!(lattice instanceof AKeyedLattice<?,?> keyed) || !(other instanceof AMap<?,?>)) return merged;
		if (merged==null) return other;
		AMap<ACell,ACell> result=(AMap<ACell,ACell>)other;
		for (Map.Entry<ACell,ACell> entry:((AMap<ACell,ACell>)merged).entrySet()) {
			if (keyed.path(entry.getKey())!=null) result=result.assoc(entry.getKey(),entry.getValue());
		}
		return (V)result;
	}

	/** Returns atomic working storage. Updating it does not publish this component. */
	public AForkableCursor<V> cursor() {
		return cursor;
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
	 * Returns the lattice declaring the shape below this component's level, or null if
	 * this component declares none. A path delivery that no child component takes is
	 * reduced to its path with these shapes before this component merges it.
	 *
	 * @return Lattice for this level, or null
	 */
	public ALattice<V> getLattice() {
		return null;
	}

	/**
	 * Resolves this component's child component for one key below its level, or null
	 * when it has none there. The default has none: a delivery below this level is
	 * then merged here, reduced to its path. A component with children of its own
	 * overrides this to hand a delivery on to them.
	 *
	 * @param <T> Value type at the key
	 * @param key Key below this level
	 * @return Child component, or null
	 */
	protected <T extends ACell> ALatticeComponent<T> child(ACell key) {
		return null;
	}

	/**
	 * Delivers a value at a path below this component. The path is walked through the
	 * components that resolve: the child component for the first key, when there is
	 * one, takes the rest of the path, and the deepest component that resolves merges
	 * the value through its own {@link #merge}, reduced to its path: the value inside
	 * the empty containers that component's lattice declares along the way, every
	 * sibling ignored. An empty path is this component's own level. A path the lattice
	 * does not declare delivers nothing, and the lattice tree ends at a signed
	 * envelope, so a complete envelope is supplied at its slot.
	 *
	 * @param value Incoming value at the path
	 * @param path Keys below this component's level
	 * @return Current working value of this component, not a publication acknowledgement
	 * @throws UnsupportedOperationException if no component on the path resolves and
	 *         this component declares no lattice to reduce the value with
	 */
	@SuppressWarnings("unchecked")
	public V mergeAt(ACell value, ACell... path) {
		if (path.length==0) return merge((V)value);
		ALatticeComponent<ACell> child=child(path[0]);
		if (child!=null) {
			child.mergeAt(value,Arrays.copyOfRange(path,1,path.length));
			return cursor.get();
		}
		V reduced=reduce(value,path);
		return (reduced==null)?cursor.get():merge(reduced);
	}

	/** The value a delivery at a path means at this level: that path alone, in the lattice's empty containers. */
	@SuppressWarnings("unchecked")
	private V reduce(ACell value, ACell[] path) {
		ALattice<?> lattice=getLattice();
		if (lattice==null) {
			throw new UnsupportedOperationException(getClass().getSimpleName()+" declares no lattice to place a value at a path below it");
		}
		ALattice<?>[] levels=new ALattice<?>[path.length];
		ALattice<?> level=lattice;
		for (int i=0; i<path.length; i++) {
			levels[i]=level;
			level=level.path(path[i]);
			if (level==null) return null;
		}
		ACell reduced=value;
		for (int i=path.length-1; i>=0; i--) {
			if (!(levels[i].zero() instanceof ADataStructure<?> empty)) return null;
			reduced=RT.assoc((ADataStructure<ACell>)empty,path[i],reduced);
			if (reduced==null) return null;
		}
		return (V)reduced;
	}

	/**
	 * Publishes one working snapshot into this component's destination using
	 * CAS-or-merge. Publication stops at that cursor: its sync is never invoked.
	 * A lightweight view has no independent boundary and simply returns its value;
	 * its edits are already visible in the containing working state.
	 *
	 * <p>Call the enclosing application's sync to run its preparation and publication,
	 * and the host's sync for persistence or replication. A caller-owned cursor
	 * supplied to a view, forked or not, is never published by the component.</p>
	 *
	 * @return Published snapshot, or current value for a lightweight view. Later
	 *         concurrent edits may remain pending in working storage.
	 */
	public V sync() {
		return owned==null?cursor.get():owned.sync();
	}

	/**
	 * Builds child views with component-supplied read/write functions. Each write
	 * function uses the corresponding lattice to construct an absent container
	 * within the atomic update. Reads and view construction never mutate state.
	 * A missing container at an unknown level causes the write to fail. Owner
	 * boundaries are not crossed here: an owner's slot is wrapped by its component with
	 * an explicitly bound signing function.
	 *
	 * @param <T> Value type at the end of the path
	 * @param cursor Cursor to navigate from
	 * @param lattice Lattice describing the value at {@code cursor}, or null for none
	 * @param path Keys to navigate
	 * @return Cursor at the path
	 */
	@SuppressWarnings("unchecked")
	public static <T extends ACell> AForkableCursor<T> resolve(AForkableCursor<?> cursor,
			ALattice<?> lattice, ACell... path) {
		AForkableCursor<ACell> c=(AForkableCursor<ACell>)cursor;
		ALattice<?> lat=lattice;
		for (ACell key: path) {
			final ALattice<?> parentLattice=lat;
			final ACell[] step={key};
			c=ProjectionCursor.create(c,
				value -> RT.getIn(value,step),
				(value,child) -> Utils.equals(RT.getIn(value,step),child)
					?value:LatticeOps.assocIn(value,child,parentLattice,step));
			lat=(lat==null)?null:lat.path(key);
		}
		return (AForkableCursor<T>)c;
	}
}
