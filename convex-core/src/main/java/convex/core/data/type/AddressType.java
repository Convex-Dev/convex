package convex.core.data.type;

import convex.core.cvm.Address;
import convex.core.data.ACell;

/**
 * Type that represents CVM Byte values
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
		return value instanceof Address;
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
		if (a instanceof Address) return (Address)a;
		return null;
	}
}
