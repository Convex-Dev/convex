package convex.core.exceptions;

import convex.core.data.Hash;

/**
 * Exception thrown when a storeless decode encounters a branch that cannot be
 * resolved from the message data alone. The message format is correct but the
 * message is partial — it contains references to data not included in the
 * encoding. A store is required to resolve the missing branches.
 */
@SuppressWarnings("serial")
public class PartialMessageException extends FastRuntimeException {

	private final Hash missingHash;

	public PartialMessageException(Hash hash) {
		super("Unresolvable branch in storeless decode: "+hash);
		this.missingHash=hash;
	}

	/**
	 * Gets the hash of the first unresolvable branch encountered.
	 * @return Hash of missing branch
	 */
	public Hash getMissingHash() {
		return missingHash;
	}
}
