package convex.lattice.generic;

import convex.core.data.ACell;
import convex.core.data.prim.AInteger;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import convex.lattice.ALattice;

/**
 * Lattice implementing a max function on natural numbers expressed as CVM integers
 */
public class MaxLattice extends ALattice<AInteger> {

	private MaxLattice() {
		// private to enforce Singleton
	}
	
	public static final MaxLattice INSTANCE = new MaxLattice();

	@Override
	public AInteger merge(AInteger ownValue, AInteger otherValue) {
		if (otherValue==null) return ownValue;
		if (ownValue==null) return otherValue;
		return (AInteger) RT.max(ownValue, otherValue);
	}

	public static MaxLattice create() {
		return INSTANCE;
	}

	@Override
	public AInteger zero() {
		return CVMLong.ZERO;
	}

	@Override
	public boolean checkForeign(AInteger value) {
		return (value instanceof AInteger);
	}

	@Override
	public <T extends ACell> ALattice<T> path(ACell childKey) {
		return null;
	}

}
