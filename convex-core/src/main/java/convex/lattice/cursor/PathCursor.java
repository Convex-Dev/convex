package convex.lattice.cursor;

import java.util.concurrent.locks.Lock;
import java.util.function.BinaryOperator;
import java.util.function.UnaryOperator;

import convex.core.data.ACell;
import convex.core.lang.RT;
import convex.core.util.Utils;
import convex.lattice.LatticeOps;
import convex.lattice.Shape;

/**
 * A cursor that navigates to a path within a base cursor's value.
 *
 * <p>Reads resolve the nested value; updates are reflected in the base cursor
 * atomically, rebuilding the value above with {@link LatticeOps#assocIn}. The base
 * cursor's {@link ACursor#shape() shape} creates the base value when a write
 * arrives through an absent one, and this cursor's own shape supplies update
 * functions with an empty value instead of null. The transactional write lock is
 * obtained from the base cursor.</p>
 *
 * <p>A path cursor with no shape is plain structural navigation: a write through an
 * absent intermediate fails rather than inventing a container. Components navigate
 * one declared level at a time with {@link ACursor#child(ACell, Shape)}.</p>
 *
 * @param <V> Type of cursor values at the path location
 */
public class PathCursor<V extends ACell> extends AForkableCursor<V> {

	private final ACursor<ACell> base;
	private final ACell[] path;
	private final Shape<V> shape;

	@SuppressWarnings({ "unchecked", "rawtypes" })
	public PathCursor(ACursor<?> base, ACell[] path, Shape<V> shape) {
		super((V) RT.getIn(base.get(), path));
		this.base=(ACursor)base;
		this.path=path.clone();
		this.shape=(shape==null)?Shape.none():shape;
	}

	public PathCursor(ACursor<?> base, ACell... path) {
		this(base,path,null);
	}

	public static <T extends ACell, V extends ACell> PathCursor<T> create(ACursor<V> base, ACell[] path) {
		return new PathCursor<>(base,path);
	}

	@Override
	public Shape<V> shape() {
		return shape;
	}


	@Override
	protected Lock exclusiveLock() {
		return base.exclusiveLock();
	}

	/**
	 * Propagates towards the root through the base cursor: a forked base converges
	 * with its parent, a live base reaches root publication.
	 */
	@Override
	public V sync() {
		if (base instanceof AForkableCursor<?> forkable) {
			forkable.sync();
			return get();
		}
		throw new IllegalStateException("Cannot sync through a non-forkable base cursor: "
			+ base.getClass().getSimpleName());
	}

	/**
	 * AssocIn through the path. The base's shape creates an absent base value;
	 * missing intermediates below it are created only for a structural shape.
	 */
	private ACell assocIn(ACell bv, ACell newValue) {
		return LatticeOps.assocIn(bv, newValue, base.shape(), path);
	}

	/**
	 * Returns the value at the path, substituting this cursor's empty value for null
	 * so update functions need no null guards for a declared position.
	 */
	private V getValueForUpdate(ACell bv) {
		V value = RT.getIn(bv, path);
		if (value == null) return shape.zero();
		return value;
	}

	@SuppressWarnings("unchecked")
	@Override
	public V get() {
		return (V) RT.getIn(base.get(), path);
	}

	@Override
	public void set(V newValue) {
		base.getAndUpdate(bv -> assocIn(bv, newValue));
	}

	@SuppressWarnings("unchecked")
	@Override
	public V getAndSet(V newValue) {
		ACell oldBase=base.getAndUpdate(bv -> assocIn(bv, newValue));
		return (V) RT.getIn(oldBase, path);
	}

	/**
	 * {@inheritDoc}
	 *
	 * <p>The update function receives this cursor's empty value instead of null for an
	 * absent value, so {@code get()} may return null while the update function sees an
	 * empty container.</p>
	 */
	@SuppressWarnings("unchecked")
	@Override
	public V getAndUpdate(UnaryOperator<V> updateFunction) {
		ACell oldBase=base.getAndUpdate(bv->{
			V oldValue=getValueForUpdate(bv);
			ACell newValue=updateFunction.apply(oldValue);
			return assocIn(bv, newValue);
		});
		return (V) RT.getIn(oldBase, path);
	}

	/**
	 * {@inheritDoc}
	 *
	 * <p>The update function receives this cursor's empty value instead of null for an
	 * absent value, so {@code get()} may return null while the update function sees an
	 * empty container.</p>
	 */
	@SuppressWarnings("unchecked")
	@Override
	public V updateAndGet(UnaryOperator<V> updateFunction) {
		ACell[] nv=new ACell[1];
		base.updateAndGet(bv->{
			V oldValue=getValueForUpdate(bv);
			V newValue=updateFunction.apply(oldValue);
			nv[0]=newValue;
			return assocIn(bv, newValue);
		});
		return (V)nv[0];
	}

	@SuppressWarnings("unchecked")
	@Override
	public V getAndAccumulate(V x, BinaryOperator<V> accumulatorFunction) {
		ACell oldBase=base.getAndUpdate(bv->{
			V oldValue=getValueForUpdate(bv);
			ACell newValue=accumulatorFunction.apply(oldValue,x);
			return assocIn(bv, newValue);
		});
		return (V) RT.getIn(oldBase, path);
	}

	@SuppressWarnings("unchecked")
	@Override
	public V accumulateAndGet(V x, BinaryOperator<V> accumulatorFunction) {
		ACell[] nv=new ACell[1];
		base.updateAndGet(bv->{
			V oldValue=getValueForUpdate(bv);
			V newValue=accumulatorFunction.apply(oldValue,x);
			nv[0]=newValue;
			return assocIn(bv, newValue);
		});
		return (V)nv[0];
	}

	/**
	 * Compare-and-set on the value at the path.
	 *
	 * <p>Compares {@code expected} against the current value at the path by
	 * {@link Utils#equals value equality}, then writes via a single
	 * compare-and-set of the whole parent value against the parent that was
	 * observed. Like any CAS this is single-shot: it returns {@code false}
	 * without retrying if {@code base} changed concurrently, so a successful
	 * ({@code true}) result always means {@code newValue} was written.</p>
	 */
	@Override
	public boolean compareAndSet(V expected, V newValue) {
		ACell parent = base.get();
		V oldValue = RT.getIn(parent, path);
		if (!Utils.equals(expected, oldValue)) return false;
		return base.compareAndSet(parent, assocIn(parent, newValue));
	}
}
