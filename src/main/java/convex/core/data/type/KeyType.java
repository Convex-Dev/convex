package convex.core.data.type;

import convex.core.data.ACell;
import convex.core.data.AccountKey;

/**
 * Type that represents CVM Byte values
 */
public final class KeyType extends AStandardType<AccountKey> {
	/**
	 * Singleton runtime instance
	 */
	public static final KeyType INSTANCE = new KeyType();

	private KeyType() {
		super (AccountKey.class);
	}
	
	@Override
	public boolean check(ACell value) {
		return value instanceof AccountKey;
	}
	
	@Override
	public String toString () {
		return "Key";
	}

	@Override
	public AccountKey defaultValue() {
		return AccountKey.ZERO;
	}

	@Override
	public AccountKey implicitCast(ACell a) {
		if (a instanceof AccountKey) return (AccountKey)a;
		return null;
	}
}
