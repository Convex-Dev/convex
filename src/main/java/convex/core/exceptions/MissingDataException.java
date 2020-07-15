package convex.core.exceptions;

import convex.core.crypto.Hash;
import convex.core.store.Stores;

/**
 * Exception thrown when an attempt is made to dereference a value that is not
 * present in the current data store.
 * 
 * Normally shouldn't be caught / referenced directly. Requires special handling
 * by Peers.
 *
 */
@SuppressWarnings("serial")
public class MissingDataException extends RuntimeException {

	private Hash hash;

	private MissingDataException(String message, Hash hash) {
		super(message);
		this.hash = hash;
	}

	public MissingDataException(Hash hash) {
		// TODO: remove inefficiency
		this("Missing " + hash + " in store " + Stores.current().toString(), hash);
	}

//	@Override
//	public Throwable fillInStackTrace() {
//		return this;
//	}

	public Hash getMissingHash() {
		return hash;
	}
}
