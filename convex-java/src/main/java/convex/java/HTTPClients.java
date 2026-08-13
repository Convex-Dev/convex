package convex.java;

import java.net.http.HttpClient;
import java.time.Duration;

/** Shared HTTP client defaults for the Convex Java clients. */
final class HTTPClients {
	private HTTPClients() {
	}

	static HttpClient getDefault() {
		return Holder.DEFAULT;
	}

	/** Defers the selector thread and connection pool until the first client is used. */
	private static final class Holder {
		private static final HttpClient DEFAULT=HttpClient.newBuilder()
				.connectTimeout(Duration.ofSeconds(30))
				.build();
	}
}
