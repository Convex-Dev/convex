package convex.core.cvm;

import convex.core.data.ACell;

/**
 * Abstract base class for CVM code constructs
 */
public abstract class ACVMCode extends ACell {

	@Override public final boolean isCVMValue() {
		// CVM code objects are CVM values by definition
		return true;
	}
}
