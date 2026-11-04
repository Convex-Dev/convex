package convex.net;

import java.io.IOException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

/**
 * Signals that resolving, reaching, identifying or admitting a peer failed in normal
 * operation: the peer could not be resolved or reached, or it was refused
 * ({@link PeerRefusedException}). The message says why, and the cause holds the
 * network error where there is one.
 *
 * <p>A connection future fails with this for an expected failure only. An attempt
 * abandoned because the peer was released or the connection manager closed is
 * cancelled instead, and misuse, such as an invalid argument, stays an unchecked
 * exception. Anything else a connection future fails with is a fault.</p>
 */
@SuppressWarnings("serial")
public class PeerConnectionException extends IOException {

	/**
	 * Creates an exception saying why the peer could not be connected.
	 *
	 * @param message why the attempt failed
	 */
	public PeerConnectionException(String message) {
		super(message);
	}

	/**
	 * Creates an exception saying why the peer could not be connected, caused by an
	 * underlying error such as a network failure.
	 *
	 * @param message why the attempt failed
	 * @param cause underlying error
	 */
	public PeerConnectionException(String message,Throwable cause) {
		super(message,cause);
	}

	/**
	 * Returns the failure to report for an attempt that failed with an error. A
	 * connection failure, a cancellation or another unchecked exception is returned as
	 * it is, so a fault is never taken for an expected failure; any other checked
	 * exception, such as a network error, becomes a connection failure it causes.
	 *
	 * @param message why the attempt failed, to which the error's message is added
	 * @param error error the attempt failed with, possibly wrapped by a future
	 * @return failure to report
	 */
	public static Throwable wrap(String message,Throwable error) {
		Throwable cause=unwrap(error);
		if (cause instanceof PeerConnectionException || cause instanceof RuntimeException || cause instanceof Error) {
			return cause;
		}
		String reason=(cause.getMessage()!=null)?cause.getMessage():cause.getClass().getSimpleName();
		return new PeerConnectionException(message+": "+reason,cause);
	}

	/**
	 * Returns the error a future failed with, without the {@link CompletionException}
	 * or {@link ExecutionException} that dependent stages and {@code get} wrap it in.
	 *
	 * @param error error as a future reports it
	 * @return the error itself
	 */
	public static Throwable unwrap(Throwable error) {
		while ((error instanceof CompletionException || error instanceof ExecutionException) && error.getCause()!=null) {
			error=error.getCause();
		}
		return error;
	}
}
