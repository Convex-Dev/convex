package convex.lattice.cursor;

import java.util.function.BinaryOperator;
import java.util.function.UnaryOperator;

import convex.core.data.ACell;
import convex.core.lang.RT;
import convex.core.util.Utils;
import convex.lattice.ALattice;
import convex.lattice.LatticeOps;

/**
 * A cursor that navigates to a path within a base cursor's value.
 *
 * <p>PathCursor provides a view into a nested location within a data structure.
 * Updates to the PathCursor are reflected in the base cursor atomically.</p>
 *
 * <p>When a {@code baseLattice} is provided, writes through null intermediates
 * use {@link LatticeOps#assocIn} to create correctly-typed containers via
 * {@code lattice.zero()} instead of defaulting to {@link convex.core.data.Maps#empty()}.
 * Additionally, update lambdas receive {@code valueLattice.zero()} instead of null
 * for non-existent values, so callers don't need manual null guards.</p>
 *
 * @param <V> Type of cursor values at the path location
 */
public class PathCursor<V extends ACell> extends AForkableCursor<V> {

	ACursor<ACell> base;
	ACell[] path;
	ALattice<?> baseLattice;
	ALattice<?> valueLattice; // lattice at the path endpoint, for zero() substitution

	@SuppressWarnings({ "unchecked", "rawtypes" })
	public PathCursor(ACursor<?> base, ACell... path) {
		super((V) base.get(path));
		this.base=(ACursor)base;
		this.path=path.clone();
		this.baseLattice=null;
		this.valueLattice=null;
	}

	@SuppressWarnings({ "unchecked", "rawtypes" })
	PathCursor(ACursor<?> base, ACell[] path, ALattice<?> baseLattice) {
		super((V) base.get(path));
		this.base=(ACursor)base;
		this.path=path.clone();
		this.baseLattice=baseLattice;
		this.valueLattice=(baseLattice != null && path.length > 0) ? baseLattice.path(path) : baseLattice;
	}

	public static <T extends ACell, V extends ACell> PathCursor<T> create(ACursor<V> base, ACell[] path) {
		return new PathCursor<>(base,path);
	}

	/**
	 * AssocIn through the path. Always uses LatticeOps.assocIn which will
	 * throw if a null intermediate has no lattice type information. Callers
	 * must either provide a lattice or ensure the base value is non-null.
	 */
	private ACell assocIn(ACell bv, ACell newValue) {
		return LatticeOps.assocIn(bv, newValue, baseLattice, path);
	}

	/**
	 * Returns the value at the path, substituting valueLattice.zero() for null
	 * when a value lattice is available. Used by update lambdas so callers
	 * don't need null guards for auto-initialised lattice paths.
	 */
	@SuppressWarnings("unchecked")
	private V getValueForUpdate(ACell bv) {
		V value = RT.getIn(bv, path);
		if (value == null && valueLattice != null) {
			return (V) valueLattice.zero();
		}
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
	 * <p>Implementation note: when a {@code valueLattice} is present, the update function receives
	 * {@code valueLattice.zero()} instead of null for non-existent values. This means
	 * {@code get()} may return null while the update function sees an empty container.
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
	 * <p>Implementation note: when a {@code valueLattice} is present, the update function receives
	 * {@code valueLattice.zero()} instead of null for non-existent values. This means
	 * {@code get()} may return null while the update function sees an empty container.
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
