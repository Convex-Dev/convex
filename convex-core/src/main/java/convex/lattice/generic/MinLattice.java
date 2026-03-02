package convex.lattice.generic;

import convex.core.data.prim.AInteger;
import convex.core.lang.RT;

/**
 * Lattice implementing a min function on CVM integers.
 */
public class MinLattice extends AValueLattice<AInteger> {

	private MinLattice() {
		// private to enforce Singleton
	}

	public static final MinLattice INSTANCE = new MinLattice();

	@Override
	public AInteger merge(AInteger ownValue, AInteger otherValue) {
		if (otherValue==null) return ownValue;
		if (ownValue==null) return otherValue;
		return (AInteger) RT.min(ownValue, otherValue);
	}

	public static MinLattice create() {
		return INSTANCE;
	}

	@Override
	public AInteger zero() {
		return null;
	}

	@Override
	public boolean checkForeign(AInteger value) {
		return (value instanceof AInteger);
	}
}
