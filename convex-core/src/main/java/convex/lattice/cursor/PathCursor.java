package convex.lattice.cursor;

import java.util.function.BinaryOperator;
import java.util.function.UnaryOperator;

import convex.core.data.ACell;
import convex.core.lang.RT;
import convex.core.util.Utils;

/**
 * A cursor that navigates to a path within a base cursor's value.
 *
 * <p>PathCursor provides a view into a nested location within a data structure.
 * Updates to the PathCursor are reflected in the base cursor atomically.</p>
 *
 * @param <V> Type of cursor values at the path location
 */
public class PathCursor<V extends ACell> extends AForkableCursor<V> {

	ACursor<ACell> base;
	ACell[] path;
	
	@SuppressWarnings({ "unchecked", "rawtypes" })
	public PathCursor(ACursor<?> base, ACell... path) {
		super((V) base.get(path));
		this.base=(ACursor)base;
		this.path=path;
	}
	
	public static <T extends ACell, V extends ACell> PathCursor<T> create(ACursor<V> base, ACell[] path) {
		return new PathCursor<>(base,path);
	}
	
	@SuppressWarnings("unchecked")
	@Override
	public V get() {
		return (V) RT.getIn(base.get(), path);
	}
	
	@Override
	public void set(V newValue) {
		base.set(newValue,path);
	}
	
	@SuppressWarnings("unchecked")
	@Override
	public V getAndSet(V newValue) {
		ACell oldBase=base.getAndUpdate(bv->{
			return RT.assocIn(bv,newValue,path);
		});
		return (V) RT.getIn(oldBase, path);
	}

	@SuppressWarnings("unchecked")
	@Override
	public V getAndUpdate(UnaryOperator<V> updateFunction) {
		ACell oldBase=base.getAndUpdate(bv->{
			V oldValue=RT.getIn(bv, path);
			ACell newValue=updateFunction.apply(oldValue);
			return RT.assocIn(bv,newValue,path);
		});
		return (V) RT.getIn(oldBase, path);
	}
	
	@SuppressWarnings("unchecked")
	@Override
	public V updateAndGet(UnaryOperator<V> updateFunction) {
		ACell[] nv=new ACell[1];
		base.updateAndGet(bv->{
			V oldValue=RT.getIn(bv, path);
			V newValue=updateFunction.apply(oldValue);
			nv[0]=newValue;
			return RT.assocIn(bv,newValue,(ACell[])path);
		});
		return (V)nv[0];
	}
	
	@SuppressWarnings("unchecked")
	@Override
	public V getAndAccumulate(V x, BinaryOperator<V> accumulatorFunction) {
		ACell oldBase=base.getAndUpdate(bv->{
			V oldValue=RT.getIn(bv, path);
			ACell newValue=accumulatorFunction.apply(x,oldValue);
			return RT.assocIn(bv,newValue,path);
		});
		return (V) RT.getIn(oldBase, path);
	}

	@SuppressWarnings("unchecked")
	@Override
	public V accumulateAndGet(V x, BinaryOperator<V> accumulatorFunction) {
		ACell[] nv=new ACell[1];
		base.updateAndGet(bv->{
			V oldValue=RT.getIn(bv, path);
			V newValue=accumulatorFunction.apply(x,oldValue);
			nv[0]=newValue;
			return RT.assocIn(bv,newValue,(ACell[])path);
		});
		return (V)nv[0];
	}

	@Override
	public boolean compareAndSet(V expected, V newValue) {
		boolean[] updated=new boolean[1];
		base.update(bv->{
			V oldValue=RT.getIn(bv, path);
			if(Utils.equals(expected,oldValue)) {
				updated[0]=true;
				return RT.assocIn(bv,newValue,(ACell[])path);
			} else {
				return bv;
			}
		});
		return updated[0];
	}








}
