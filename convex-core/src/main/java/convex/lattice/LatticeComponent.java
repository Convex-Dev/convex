package convex.lattice;

import java.util.Map;
import java.util.Objects;

import convex.core.data.ACell;
import convex.core.data.ADataStructure;
import convex.core.data.AMap;
import convex.core.lang.RT;
import convex.lattice.cursor.AForkableCursor;
import convex.lattice.cursor.PathCursor;
import convex.lattice.generic.AKeyedLattice;

/**
 * Optional adapter whose incoming merge, path reduction and publication use a supplied lattice.
 * Useful for simple values and caller-supplied lattice implementations. Domain
 * components normally extend {@link ALatticeComponent} and call reusable lattice
 * rules only where their operations need them. A staged adapter publishes only at
 * its destination cursor; a live view writes there immediately.
 *
 * @param <V> Value at this component's position
 */
public class LatticeComponent<V extends ACell> extends ALatticeComponent<V> {
	private final ALattice<V> lattice;
	private volatile LatticeContext context=LatticeContext.EMPTY;

	/** Optional policy for the supplied lattice implementation, local to this adapter. */
	public final LatticeContext getContext() { return context; }

	/** Configures this adapter only; component bases and hosts carry no context. */
	public final void setContext(LatticeContext context) {
		this.context=(context==null)?LatticeContext.EMPTY:context;
	}

	/** Binds supplied working storage and optional policy for the lattice adapter. */
	public LatticeComponent(ALattice<V> lattice,AForkableCursor<V> cursor,LatticeContext context) {
		this(lattice,cursor);
		setContext(context);
	}

	/** Binds supplied working storage without creating a fork. */
	public LatticeComponent(ALattice<V> lattice,AForkableCursor<V> cursor) {
		super(cursor);
		this.lattice=Objects.requireNonNull(lattice,"Component lattice must not be null");
	}

	@Override
	public LatticeComponent<V> rebind(AForkableCursor<V> cursor) {
		return new LatticeComponent<>(lattice,cursor,context);
	}

	@Override
	public LatticeComponent<V> fork() {
		return rebind(storage.fork());
	}

	/** Binds a component directly to the supplied cursor. */
	public static <V extends ACell> LatticeComponent<V> live(ALattice<V> lattice,
			AForkableCursor<V> cursor,LatticeContext context) {
		return new LatticeComponent<>(lattice,cursor,context);
	}

	/** Returns the optional lattice implementation used by this adapter. */
	public ALattice<V> getLattice() {
		return lattice;
	}

	@Override
	public V merge(V incoming) {
		return cursor().updateAndGet(own -> lattice.merge(getContext(),own,incoming));
	}

	/** Reconciles declared lattice values while preserving unrelated destination keys. */
	@Override
	protected V reconcile(V own,V other) {
		return reconcile(lattice,getContext(),own,other);
	}

	@Override
	protected V mergeAtPath(ACell value,ACell... path) {
		V reduced=reduce(lattice,value,path);
		return reduced==null?cursor().getOrDefault(null):merge(reduced);
	}

	/**
	 * Joins changes on both sides by a lattice, keeping the destination's keys that a
	 * keyed lattice does not define.
	 *
	 * @param <V> Value type
	 * @param lattice Lattice of the value
	 * @param context Policy for the lattice
	 * @param own Working value
	 * @param other Destination value
	 * @return Joined value
	 */
	@SuppressWarnings("unchecked")
	public static <V extends ACell> V reconcile(ALattice<V> lattice,LatticeContext context,V own,V other) {
		// Own first: tie rules prefer that side (see LWWLattice), so the order decides the value
		V merged=lattice.merge(context,own,other);
		if (!(lattice instanceof AKeyedLattice<?,?> keyed) || (other!=null && !(other instanceof AMap<?,?>))) return merged;
		if (merged==null) return other;
		AMap<ACell,ACell> result=(AMap<ACell,ACell>)other;
		for (Map.Entry<ACell,ACell> entry:((AMap<ACell,ACell>)merged).entrySet()) {
			ACell key=entry.getKey();
			if (keyed.path(key)!=null) {
				// Rebuild an absent destination only for keys this component owns
				if (result==null) result=(AMap<ACell,ACell>)keyed.zero();
				result=result.assoc(key,entry.getValue());
			}
		}
		return (V)result;
	}

	/**
	 * Returns what a delivery at a path means at the lattice's level: that path alone,
	 * in the lattice's empty containers.
	 *
	 * @param <V> Value type
	 * @param lattice Lattice at the delivery's level
	 * @param value Value delivered at the path
	 * @param path Path of the delivery
	 * @return Value at the lattice's level, or null if the lattice cannot hold the path
	 */
	@SuppressWarnings("unchecked")
	public static <V extends ACell> V reduce(ALattice<V> lattice,ACell value,ACell... path) {
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
	 * Writes a value at a path as {@link PathCursor#assocExisting} does, except that
	 * an absent base is created as the lattice's empty value. Containers below the base
	 * are never created; an existing container is used whatever the lattice.
	 *
	 * @param <T> Return type
	 * @param data Value holding the path, or null
	 * @param value Value to write at the end of the path
	 * @param lattice Lattice of {@code data}'s position, or null for none
	 * @param keys Path of keys to navigate
	 * @return Updated value
	 * @throws PathWriteException If the base is absent and the lattice has no empty
	 *         value, or as {@link PathCursor#assocExisting}
	 */
	public static <T extends ACell> T assocIn(ACell data,ACell value,ALattice<?> lattice,ACell... keys) {
		if (data==null && keys.length>0 && lattice!=null) data=lattice.zero();
		return PathCursor.assocExisting(data,value,keys);
	}
}
