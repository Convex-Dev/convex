package convex.core.util;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
	 * @return
	 */
	public synchronized static ExecutorService getVirtualExecutor() {
		if (virtualExecutor==null) {
			virtualExecutor=buildVirtualExecutor();
			
			Shutdown.addHook(Shutdown.EXECUTOR, ()-> {
				ExecutorService executor=virtualExecutor;
				if (executor==null) return;
				// Try a gentle termination. If not fast enough, terminate with extreme prejudice
				executor.shutdown();
				try {
				    if (!executor.awaitTermination(200, TimeUnit.MILLISECONDS)) {
				    	executor.shutdownNow();
				    } 
				} catch (InterruptedException e) {
					executor.shutdownNow();
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
		} catch (Exception e) {
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
	 * @throws InterruptedException
	 * @throws ExecutionException
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
	 * Runs a (probably IO-bound) task in a virtual thread if available, 
	 * @param task Task to run
	 */
	public static void runVirtual(Runnable task) {
		getVirtualExecutor().execute(task);
	}

}
