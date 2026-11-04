package convex.net;

/**
 * Signals that a peer was reached but not admitted: it failed the identity challenge,
 * proved a key other than the one expected or none at all, turned out to be this
 * node, or this node's policy refuses it.
 */
@SuppressWarnings("serial")
public class PeerRefusedException extends PeerConnectionException {

	/**
	 * Creates an exception saying why the peer was refused.
	 *
	 * @param message why the peer was refused
	 */
	public PeerRefusedException(String message) {
		super(message);
	}

	/**
	 * Creates an exception saying why the peer was refused, caused by an underlying error.
	 *
	 * @param message why the peer was refused
	 * @param cause underlying error
	 */
	public PeerRefusedException(String message,Throwable cause) {
		super(message,cause);
	}
}
