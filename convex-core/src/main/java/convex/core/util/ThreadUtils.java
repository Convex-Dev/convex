package convex.core.util;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Utilities for threading and concurrency
 */
public class ThreadUtils {

	private static ExecutorService virtualExecutor = null;

	/**
	 * Get the current virtual thread ExecutorService, intended for IO-bound blocking operations 
	 * @return Executor service
	 */
	public synchronized static ExecutorService getVirtualExecutor() {
		if (virtualExecutor==null) {
			virtualExecutor=buildVirtualExecutor();
			
			Shutdown.addHook(Shutdown.EXECUTOR, ()-> {
				ExecutorService executor=virtualExecutor;
				List<Runnable> tasks=List.of();
				if (executor==null) return;
				try {
					// Try a gentle termination. If not fast enough, terminate with extreme prejudice
					executor.shutdown();
				    if (!executor.awaitTermination(5000, TimeUnit.MILLISECONDS)) {
				    	// These are tasks still awaiting execution
				    	tasks=executor.shutdownNow();
				    	if (!tasks.isEmpty()) {
				    		System.err.println("Still pending executor tasks: "+tasks);
				    	}
				    	if (!executor.awaitTermination(10000, TimeUnit.MILLISECONDS)) {
					    	System.err.println("Slow shutdown of executor task threads");
				    	}     
				    } 
				} catch (InterruptedException e) {
					executor.shutdownNow();
					Thread.currentThread().interrupt();
				} 
			});
		}
		return virtualExecutor;
	}

	private static ExecutorService buildVirtualExecutor() {
		ExecutorService ex;
		try{
		    Method method = Executors.class.getMethod("newVirtualThreadPerTaskExecutor");
		    ex = (ExecutorService) method.invoke(null);
		} catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException e) {
			// fall back to a cached thread pool
		    ex = Executors.newCachedThreadPool();
		}
		return ex;
	}

	/**
	 * Executes functions on for each element of a collection, returning a list of futures
	 * @param <R> Result type of function
	 * @param <T> Argument type
	 * @param executor ExecutorService with which to run functions
	 * @param f Function to run
	 * @param items Collection of items to run futures on
	 * @return List of futures for each item
	 */
	public static <R,T> ArrayList<CompletableFuture<R>> futureMap(ExecutorService executor,Function<? super T,R> f, Collection<T> items) {
		ArrayList<CompletableFuture<R>> futures=new ArrayList<>(items.size());
		for (T item: items) {
			futures.add(CompletableFuture.supplyAsync(()->f.apply(item),executor));
		}
		return futures;
	}

	/**
	 * Awaits the result of all of a collection of futures
	 * @param <R> Type of futures
	 * @param futures A collection of futures
	 */
	public static <R> void awaitAll(Collection<CompletableFuture<R>> futures) throws InterruptedException, ExecutionException {
		CompletableFuture.allOf(futures.toArray(new CompletableFuture[futures.size()])).get();
	}

	@SuppressWarnings("unchecked")
	public static <T> CompletableFuture<java.util.List<T>> completeAll(java.util.List<CompletableFuture<T>> futures) {
	    CompletableFuture<T>[] fs = futures.toArray(new CompletableFuture[futures.size()]);
	
	    return CompletableFuture.allOf(fs).thenApply(e -> futures.stream()
	    				.map(CompletableFuture::join)
	    				.collect(Collectors.toList())
	    				);
	}

	/**
	 * Runs a task in a virtual thread with a given name via the shared executor.
	 * @param name Short name for the virtual thread (for debugging/tracking)
	 * @param task Task to run
	 */
	public static void runVirtual(String name, Runnable task) {
		getVirtualExecutor().execute(() -> {
			Thread.currentThread().setName(name);
			task.run();
		});
	}

	/**
	 * Daemon platform threads for blocking calls that must not be interrupted. Threads
	 * start on demand and expire when idle; the pool is never shut down and its tasks
	 * are never cancelled, so nothing interrupts them. Platform threads, because callers
	 * may wait while holding a monitor: on JDK 21 to 23, virtual threads queued on that
	 * monitor pin their carriers and could leave a virtual worker no carrier to run on.
	 */
	private static final ExecutorService UNINTERRUPTIBLE=Executors.newCachedThreadPool(r -> {
		Thread t=new Thread(r,"convex-uninterruptible");
		t.setDaemon(true);
		return t;
	});

	/** A blocking call returning a value. */
	@FunctionalInterface
	public interface BlockingCall<T,E extends Exception> {
		T call() throws E;
	}

	/** A blocking call returning nothing. */
	@FunctionalInterface
	public interface BlockingAction<E extends Exception> {
		void run() throws E;
	}

	/**
	 * Runs a blocking call to completion on a pooled thread that nothing interrupts.
	 * The caller waits without being interruptible, then restores its own interrupt
	 * status. Use this for calls that an interrupt would break for other threads, such
	 * as operations on a shared {@code FileChannel}, which Java closes when a thread
	 * using it is interrupted. The call must not wait on the calling thread, or on
	 * locks it holds.
	 *
	 * @param <T> Result type
	 * @param <E> Checked exception the call may throw
	 * @param call Blocking call
	 * @return Result of the call
	 * @throws E If the call throws it
	 */
	@SuppressWarnings("unchecked")
	public static <T,E extends Exception> T callUninterruptibly(BlockingCall<T,E> call) throws E {
		Future<T> future=UNINTERRUPTIBLE.submit(call::call);
		boolean interrupted=false;
		try {
			while (true) {
				try {
					return future.get();
				} catch (InterruptedException e) {
					interrupted=true;
				} catch (ExecutionException e) {
					Throwable cause=e.getCause();
					if (cause instanceof RuntimeException runtime) throw runtime;
					if (cause instanceof Error error) throw error;
					// The call declares no other checked exception
					throw (E)cause;
				}
			}
		} finally {
			if (interrupted) Thread.currentThread().interrupt();
		}
	}

	/**
	 * Runs a blocking action to completion on a pooled thread that nothing interrupts,
	 * as {@link #callUninterruptibly}.
	 *
	 * @param <E> Checked exception the action may throw
	 * @param action Blocking action
	 * @throws E If the action throws it
	 */
	public static <E extends Exception> void runUninterruptibly(BlockingAction<E> action) throws E {
		ThreadUtils.<Void,E>callUninterruptibly(() -> {
			action.run();
			return null;
		});
	}
}
