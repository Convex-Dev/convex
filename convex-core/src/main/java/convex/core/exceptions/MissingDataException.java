package convex.core.exceptions;

import convex.core.data.Hash;
import convex.core.store.AStore;

/**
 * Exception thrown when an attempt is made to dereference a value that is not
 * present in the current data store.
 * 
 * Normally shouldn't be caught / referenced directly. Requires special handling
 * by Peers.
 *
 */
@SuppressWarnings("serial")
public class MissingDataException extends FastRuntimeException {

	private Hash hash;
	private AStore store;

	public MissingDataException(AStore store, Hash hash) {
		super("Missing Data");
		this.hash = hash;
		this.store=store;
	}

	public String getMessage() {
		return "Missing hash:" + hash + ((store==null)?" (null store)": "in store " + store.toString());
	}

	/**
	 * Gets the Hash for the missing data
	 * @return Hash value
	 */
	public Hash getMissingHash() {
		return hash;
	}
	
	/**
	 * Gets the Store for which the missing data exception occurred
	 * @return Store instance
	 */
	public AStore getStore() {
		return store;
	}
}
