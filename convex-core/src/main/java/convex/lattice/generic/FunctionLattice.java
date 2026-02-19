package convex.lattice.generic;

import java.util.function.BinaryOperator;

import convex.core.data.ACell;

/**
 * Lattice with a caller-provided merge function.
 *
 * <p>The caller is responsible for ensuring the function satisfies lattice laws
 * (commutativity, associativity, idempotency). Null handling is provided
 * by this class — the function is only called with two non-null values.</p>
 *
 * @param <V> Type of lattice values
 */
public class FunctionLattice<V extends ACell> extends AValueLattice<V> {

	private final BinaryOperator<V> mergeFunction;

	private FunctionLattice(BinaryOperator<V> mergeFunction) {
		this.mergeFunction = mergeFunction;
	}

	public static <V extends ACell> FunctionLattice<V> create(BinaryOperator<V> mergeFunction) {
		return new FunctionLattice<>(mergeFunction);
	}

	@Override
	public V merge(V ownValue, V otherValue) {
		if (otherValue==null) return ownValue;
		if (ownValue==null) return otherValue;
		return mergeFunction.apply(ownValue, otherValue);
	}

	@Override
	public V zero() {
		return null;
	}

	@Override
	public boolean checkForeign(V value) {
		return true;
	}
}
