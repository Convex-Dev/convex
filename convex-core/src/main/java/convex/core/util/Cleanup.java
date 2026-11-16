package convex.core.util;

import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * Runs the steps of a close in order: every step runs whatever earlier steps threw,
 * and {@link #finish(Class)} then reports the failures. Not safe for use by more
 * than one thread.
 */
public final class Cleanup {

	private Throwable failure;

	/**
	 * Runs a step, recording any failure it throws, JVM errors included. An
	 * InterruptedException also restores the thread's interrupt flag.
	 *
	 * @param step Step to run
	 */
	public void run(UnsafeRunnable step) {
		try {
			step.run();
		} catch (Throwable t) {
			if (t instanceof InterruptedException) Thread.currentThread().interrupt();
			record(t);
		}
	}

	private void record(Throwable t) {
		if (failure==null) {
			failure=t;
		} else if (failure!=t) {
			failure.addSuppressed(t);
		}
	}

	/**
	 * Throws the first recorded failure, with later ones added to it as suppressed,
	 * or returns if no step failed. Unchecked failures, and failures of the declared
	 * type, are thrown as they are. Any other checked failure is wrapped: an
	 * IOException in an UncheckedIOException, anything else in a RuntimeException.
	 *
	 * @param <E> Checked exception type the caller declares
	 * @param declared Class of that type
	 * @throws E The first failure, if it is of the declared type
	 */
	public <E extends Exception> void finish(Class<E> declared) throws E {
		Throwable t=failure;
		if (t==null) return;
		if (t instanceof RuntimeException e) throw e;
		if (t instanceof Error e) throw e;
		if (declared.isInstance(t)) throw declared.cast(t);
		if (t instanceof IOException e) throw new UncheckedIOException(e);
		throw new RuntimeException(t);
	}

	/**
	 * As {@link #finish(Class)}, for a close that declares no checked exception.
	 */
	public void finish() {
		finish(RuntimeException.class);
	}
}
