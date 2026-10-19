package convex.lattice.cursor;

import java.util.concurrent.locks.Lock;
import java.util.function.BinaryOperator;
import java.util.function.UnaryOperator;

import convex.core.data.ACell;
import convex.core.lang.RT;
import convex.core.util.Utils;
import convex.lattice.LatticeOps;

/**
 * A cursor that navigates to a path within a base cursor's value.
 *
 * <p>Reads resolve the nested value; updates rebuild existing ancestors atomically.
 * A write through a missing container fails. Update functions receive the actual
 * value, including null; components supply any defaults or construction logic.
 * The transactional write lock is obtained from the base cursor.</p>
 *
 * @param <V> Type of cursor values at the path location
 */
public class PathCursor<V extends ACell> extends AForkableCursor<V> {

	private final ACursor<ACell> base;
	private final ACell[] path;

	@SuppressWarnings({ "unchecked", "rawtypes" })
	public PathCursor(ACursor<?> base, ACell... path) {
		this.base=(ACursor)base;
		this.path=path.clone();
	}

	public static <T extends ACell, V extends ACell> PathCursor<T> create(ACursor<V> base, ACell[] path) {
		return new PathCursor<>(base,path);
	}


	@Override
	protected Lock exclusiveLock() {
		return base.exclusiveLock();
	}

	/**
	 * Associates through existing containers only.
	 */
	private ACell assocIn(ACell bv, ACell newValue) {
		if (Utils.equals(RT.getIn(bv,path),newValue)) return bv;
		return LatticeOps.assocExisting(bv, newValue, path);
	}

	/**
	 * Returns the actual value at the path, including null.
	 */
	private V getValueForUpdate(ACell bv) {
		return RT.getIn(bv, path);
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
