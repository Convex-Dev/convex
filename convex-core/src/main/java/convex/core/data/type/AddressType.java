package convex.core.data.type;

import convex.core.cvm.Address;
import convex.core.data.ACell;
import convex.core.lang.RT;

/**
 * Type that represents CVM addresses, including generic CAD3 representations.
 */
public final class AddressType extends AStandardType<Address> {
	/**
	 * Singleton runtime instance
	 */
	public static final AddressType INSTANCE = new AddressType();

	private AddressType() {
		super (Address.class);
	}
	
	@Override
	public boolean check(ACell value) {
		return RT.ensureAddress(value)!=null;
	}
	
	@Override
	public String toString () {
		return "Address";
	}

	@Override
	public Address defaultValue() {
		return Address.ZERO;
	}

	@Override
	public Address implicitCast(ACell a) {
		return RT.ensureAddress(a);
	}
}
