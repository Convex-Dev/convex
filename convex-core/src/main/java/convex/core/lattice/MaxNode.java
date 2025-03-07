package convex.core.lattice;

import convex.core.data.prim.AInteger;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;

public class MaxNode extends ALattice<AInteger> {

	private static final MaxNode INSTANCE = new MaxNode();

	@Override
	public AInteger merge(AInteger ownValue, AInteger otherValue) {
		if (otherValue==null) return ownValue;
		if (ownValue==null) return otherValue;
		return (AInteger) RT.max(ownValue, otherValue);
	}

	public static MaxNode create() {
		return INSTANCE;
	}

	@Override
	public AInteger zero() {
		return CVMLong.ZERO;
	}

}
