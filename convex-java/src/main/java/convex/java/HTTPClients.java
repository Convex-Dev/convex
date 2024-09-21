package convex.java;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import org.apache.hc.client5.http.async.methods.SimpleHttpRequest;
import org.apache.hc.client5.http.async.methods.SimpleHttpResponse;
import org.apache.hc.client5.http.impl.async.CloseableHttpAsyncClient;
import org.apache.hc.client5.http.impl.async.HttpAsyncClients;
import org.apache.hc.core5.concurrent.FutureCallback;

import convex.core.util.Shutdown;

public class HTTPClients {
	private static final CloseableHttpAsyncClient httpasyncclient ;

	static {
		httpasyncclient = HttpAsyncClients.createDefault();
		Shutdown.addHook(Shutdown.CLIENTHTTP, ()->{
			try {
				httpasyncclient.close();
			} catch (IOException e) {
				// ignore, probably dead anyway
			}
		});
		httpasyncclient.start();
	}

	public static CompletableFuture<SimpleHttpResponse> execute(SimpleHttpRequest request) {
		CompletableFuture<SimpleHttpResponse> future=toCompletableFuture(fc -> {
			httpasyncclient.execute(request, (FutureCallback<SimpleHttpResponse>) fc);
		});
		return future;
	}
	
	private static <T> CompletableFuture<T> toCompletableFuture(Consumer<FutureCallback<T>> c) {
        CompletableFuture<T> promise = new CompletableFuture<>();

        c.accept(new FutureCallback<T>() {
            @Override
            public void completed(T t) {
                promise.complete(t);
            }

            @Override
            public void failed(Exception e) {
                promise.completeExceptionally(e);
            }

            @Override
            public void cancelled() {
                promise.cancel(true);
            }
        });
        return promise;
    }
}
