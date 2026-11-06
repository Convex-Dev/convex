package convex.lattice;

import java.io.IOException;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * The schedule shared by a host's automatic workers, {@link AutoSync} and
 * {@link AutoFlush}: one virtual thread runs a step at a fixed delay, the first
 * failure is reported and later runs continue, and stopping drains the thread.
 */
final class HostWorker {
	/** One run of a worker, or its last step when it stops. */
	interface Step {
		void run() throws IOException;
	}

	private final ScheduledExecutorService executor;
	private final CompletableFuture<Throwable> failure=new CompletableFuture<>();
	private volatile Thread thread;

	HostWorker(String name,Duration interval,Step step) {
		long nanos=Objects.requireNonNull(interval).toNanos();
		if (nanos<=0) throw new IllegalArgumentException("Interval must be positive");
		Objects.requireNonNull(step);
		executor=Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().name(name).factory());
		executor.scheduleWithFixedDelay(() -> run(step),nanos,nanos,TimeUnit.NANOSECONDS);
	}

	/** Whether a failure is one no service can carry on from, so it is thrown rather than reported. */
	static boolean isFatal(Throwable e) {
		return (e instanceof VirtualMachineError) && !(e instanceof StackOverflowError);
	}

	CompletionStage<Throwable> failure() {
		return failure.minimalCompletionStage();
	}

	/** Reports a failure, if none has been reported yet. */
	void report(Throwable e) {
		failure.complete(e);
	}

	/** Whether the caller is this worker's thread, such as a failure callback. */
	boolean isWorker() {
		return Thread.currentThread()==thread;
	}

	private void run(Step step) {
		thread=Thread.currentThread();
		try {
			step.run();
		} catch (Throwable e) {
			// Includes EtchCorruptionError. A periodic task that throws is never run
			// again, so every recoverable failure is reported and the schedule continues.
			if (isFatal(e)) throw (Error)e;
			report(e);
		}
	}

	/**
	 * Stops the schedule, waits for a run in progress, then runs the last step, if any.
	 * Calls from the worker's own thread are refused by the owning worker before this.
	 *
	 * @param last Step to run once the worker has stopped, or null for none
	 * @throws IOException if the last step fails
	 */
	void stop(Step last) throws IOException {
		executor.shutdown();
		// A surrounding resource scope may close the store as soon as we return.
		// Drain and finish uninterrupted, then restore the owner's interruption.
		// Clear it first: awaitTermination ignores it once the worker has stopped.
		boolean interrupted=Thread.interrupted();
		try {
			for (;;) {
				try {
					if (executor.awaitTermination(Long.MAX_VALUE,TimeUnit.NANOSECONDS)) break;
				} catch (InterruptedException e) {
					interrupted=true;
				}
			}
			if (last!=null) last.run();
		} finally {
			if (interrupted) Thread.currentThread().interrupt();
		}
	}
}
