package convex.core.util;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Function;

/** Completion stages that own their upstream operation, including cancellation. */
public final class FutureUtils {
	private FutureUtils() {}

	/** Like thenApply, with cancellation forwarded to the owned source. */
	public static <S,T> CompletableFuture<T> map(CompletableFuture<S> source,Function<? super S,? extends T> mapper) {
		return handle(source,(value,error) -> {
			if (error!=null) throw new CompletionException(error);
			return mapper.apply(value);
		});
	}

	/**
	 * Like handle, but cancellation (or an added timeout) also ends the source
	 * before notifying callers. Use only for an exclusively owned source.
	 */
	public static <S,T> CompletableFuture<T> handle(CompletableFuture<S> source,
			BiFunction<? super S,Throwable,? extends T> handler) {
		Objects.requireNonNull(source);
		Objects.requireNonNull(handler);
		LinkedFuture<S,T> result=new LinkedFuture<>(source,handler);
		source.whenComplete(result);
		return result;
	}

	/** Also acts as the completion callback, avoiding a separate capturing lambda. */
	private static final class LinkedFuture<S,T> extends CompletableFuture<T> implements BiConsumer<S,Throwable> {
		private volatile CompletableFuture<?> source;
		private volatile BiFunction<? super S,Throwable,? extends T> handler;
		private volatile boolean finishing;

		LinkedFuture(CompletableFuture<S> source,BiFunction<? super S,Throwable,? extends T> handler) {
			this.source=source;
			this.handler=handler;
		}

		@Override public void accept(S value,Throwable error) {
			var interpret=handler;
			if (finishing || interpret==null) return;
			try {
				if (error instanceof CancellationException) resolve(null,error);
				else resolve(interpret.apply(value,error),null);
			} catch (Throwable failure) {
				resolve(null,failure);
			}
		}

		private void detach(boolean interrupt) {
			// Suppress the source's completion relay while cancellation propagates.
			// Otherwise a handler could publish a normal value before cancel returns.
			finishing=true;
			CompletableFuture<?> upstream=source;
			if (upstream!=null) upstream.cancel(interrupt);
			source=null;
			handler=null;
		}

		void resolve(T value,Throwable error) {
			if (finishing) return;
			source=null;
			handler=null;
			if (error==null) super.complete(value);
			else super.completeExceptionally(error);
		}

		@Override public boolean cancel(boolean interrupt) {
			if (isDone()) return isCancelled();
			detach(interrupt);
			return super.cancel(interrupt);
		}

		@Override public boolean completeExceptionally(Throwable error) {
			Objects.requireNonNull(error);
			if (isDone()) return false;
			detach(false);
			return super.completeExceptionally(error);
		}

		@Override public boolean complete(T value) {
			if (isDone()) return false;
			detach(false);
			return super.complete(value);
		}
	}
}
