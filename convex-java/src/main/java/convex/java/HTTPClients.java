package convex.java;

import java.io.IOException;

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

	public static void execute(SimpleHttpRequest request, FutureCallback<SimpleHttpResponse> fc) {
		httpasyncclient.execute(request, fc);
	}
}
