package convex.restapi.handler;

import io.javalin.http.Context;
import io.javalin.http.Handler;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import javax.naming.ServiceUnavailableException;

import convex.restapi.api.ABaseAPI;

/**
 * Admission control for a route: bounds how many requests may be in flight at once,
 * server-wide and optionally per calling client.
 *
 * <p>A permit is held for the whole handler, so on routes that wait for consensus it is
 * held for as long as that takes. The two dimensions do different jobs. The server-wide
 * cap bounds total resource use. The per-client cap stops one caller consuming the whole
 * server-wide allowance, which a single client can otherwise do — that is the difference
 * between one heavy user degrading itself and degrading everyone.</p>
 *
 * <p>Requests are queued rather than rejected outright: a caller waits up to the busy
 * timeout for a permit and only then receives a 503. Because request handling runs on
 * virtual threads, a waiting request costs little more than its stack, so a
 * server-wide cap in the thousands is a real control rather than one shadowed by an
 * underlying platform thread pool.</p>
 *
 * <p><b>Clients are identified by remote address</b>, not by connection. Per-connection
 * accounting would be trivially defeated by opening more connections, so it would not
 * bound anything. Address-based accounting is defeated only by controlling more
 * addresses, and the server-wide cap remains the backstop when that happens.</p>
 */
public class ConcurrentLimit {

	/** Bounds total in-flight requests for this route. */
	private final Semaphore semaphore;

	/** Maximum in-flight requests for any one client, or 0 for no per-client bound. */
	private final int maxPerClient;

	/**
	 * In-flight counts per client. Entries exist only while a client has a request in
	 * flight and are removed as soon as its last one finishes, so an attacker cycling
	 * through addresses cannot grow this map beyond what the server-wide cap allows to
	 * be in flight at once.
	 */
	final ConcurrentHashMap<String, int[]> clientCounts = new ConcurrentHashMap<>();

	private final long timeout;

	public ConcurrentLimit(int maxRequests) {
		this(maxRequests, 0, ABaseAPI.BUSY_TIMEOUT);
	}

	public ConcurrentLimit(int maxRequests, long timeoutMillis) {
		this(maxRequests, 0, timeoutMillis);
	}

	/**
	 * Creates a limit bounding both total and per-client in-flight requests.
	 *
	 * @param maxRequests Maximum in-flight requests server-wide
	 * @param maxPerClient Maximum in-flight requests per client, or 0 for no per-client bound
	 */
	public ConcurrentLimit(int maxRequests, int maxPerClient) {
		this(maxRequests, maxPerClient, ABaseAPI.BUSY_TIMEOUT);
	}

	public ConcurrentLimit(int maxRequests, int maxPerClient, long timeoutMillis) {
		if (maxRequests < 1) throw new IllegalArgumentException("Concurrency limit must be at least 1");
		if (maxPerClient < 0) throw new IllegalArgumentException("Per-client limit must not be negative");
		this.semaphore = new Semaphore(maxRequests);
		this.maxPerClient = maxPerClient;
		this.timeout = timeoutMillis;
	}

	public Handler handler(Handler delegate) {
		return ctx -> {
			String client = clientKey(ctx);
			// Take the per-client slot first: it is cheap and non-blocking, so a client
			// already at its own limit is turned away without occupying a server-wide
			// permit that another client could have used.
			if (!acquireClient(client)) {
				throw new ServiceUnavailableException("Too many concurrent requests from this client");
			}
			// Only reached when acquireClient incremented, so release is always balanced
			try {
				if (!semaphore.tryAcquire(timeout, TimeUnit.MILLISECONDS)) {
					throw new ServiceUnavailableException("Server busy: too many requests");
				}
				try {
					delegate.handle(ctx);
				} finally {
					semaphore.release();
				}
			} finally {
				releaseClient(client);
			}
		};
	}

	// Package-private so the accounting can be tested directly, without a Context
	boolean acquireClient(String client) {
		if ((maxPerClient <= 0) || (client == null)) return true;
		// compute() holds the bin lock across the whole read-modify-write, so two
		// requests from one client cannot both observe a stale count and exceed the
		// limit. The flag records whether we actually incremented, so release only ever
		// undoes an increment this call made.
		boolean[] acquired = new boolean[1];
		clientCounts.compute(client, (key, count) -> {
			if (count == null) {
				acquired[0] = true;
				return new int[] { 1 };
			}
			if (count[0] >= maxPerClient) return count;
			count[0]++;
			acquired[0] = true;
			return count;
		});
		return acquired[0];
	}

	void releaseClient(String client) {
		if ((maxPerClient <= 0) || (client == null)) return;
		clientCounts.computeIfPresent(client, (key, count) -> {
			count[0]--;
			return (count[0] <= 0) ? null : count;
		});
	}

	/** Identifies the calling client. Null disables per-client accounting for a request. */
	private static String clientKey(Context ctx) {
		try {
			return ctx.ip();
		} catch (RuntimeException e) {
			// Never fail a request because the remote address could not be determined;
			// the server-wide cap still applies
			return null;
		}
	}
}
