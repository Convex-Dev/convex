package convex.restapi.handler;

import java.io.ByteArrayInputStream;
import java.io.InputStream;

import io.javalin.http.Context;

/**
 * Safe access to structured REST request bodies.
 *
 * <p>Javalin enforces its configured request-size limit in
 * {@link Context#bodyAsBytes()}, but its raw {@link Context#bodyInputStream()}
 * bypasses that limit. Buffer the bounded body before giving it to a streaming
 * parser so chunked requests are subject to the same limit as requests with a
 * declared content length.</p>
 *
 * <p>This helper is intentionally for structured messages which are ultimately
 * materialised in memory. A future large-file upload endpoint should instead
 * stream to its destination through its own explicit byte and time limits.</p>
 */
public final class RequestBody {

	private RequestBody() {
	}

	/**
	 * Returns a stream over a complete request body already bounded by Javalin.
	 *
	 * @param ctx Request context
	 * @return Stream over the bounded, buffered request body
	 */
	public static InputStream boundedInputStream(Context ctx) {
		return new ByteArrayInputStream(ctx.bodyAsBytes());
	}
}
