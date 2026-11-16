package convex.core.exceptions;

/**
 * Signals data that is well formed but false: not what it claims to be, or not what
 * was asked for, such as a signature that does not verify or a reply that does not
 * match its request. No correct implementation sends such data, so receiving it
 * from a remote proves the remote misbehaved.
 */
@SuppressWarnings("serial")
public class MisbehaviourException extends ValidationException {

	public MisbehaviourException(String message) {
		super(message);
	}

	public MisbehaviourException(String message, Throwable cause) {
		super(message, cause);
	}
}
