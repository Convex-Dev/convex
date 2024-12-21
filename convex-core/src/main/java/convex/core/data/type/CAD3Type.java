package convex.core.data.type;

import convex.core.data.ACell;
import convex.core.data.prim.ByteFlag;

/**
 * Type for CAD3 Values not recognised by CVM
 */
public class CAD3Type extends AType {
	/**
	 * Singleton runtime instance
	 */
	public static final CAD3Type INSTANCE = new CAD3Type();

	private CAD3Type() {
		super ();
	}
	
	@Override
	public boolean check(ACell value) {
		return true;
	}
	
	@Override
	public String toString () {
		return "CAD3";
	}

	@Override
	public ACell defaultValue() {
		return ByteFlag.create(15);
	}

	@Override
	public ACell implicitCast(ACell a) {
		return a;
	}

	@Override
	public boolean allowsNull() {
		return false;
	}

	@Override
	public Class<? extends ACell> getJavaClass() {
		return ACell.class;
	}
}
