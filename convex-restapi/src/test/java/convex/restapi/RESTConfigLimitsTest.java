package convex.restapi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for the public-surface limit accessors on {@link RESTConfig}:
 * global request cap, request body size and the public query lane. Pure and
 * deterministic: no server, no HTTP.
 */
public class RESTConfigLimitsTest {

	@Test
	public void testDefaults() {
		RESTConfig c=RESTConfig.parse("{}");
		assertEquals(RESTConfig.DEFAULT_MAX_CONCURRENT_REQUESTS, c.getMaxConcurrentRequests());
		assertEquals(RESTConfig.DEFAULT_MAX_REQUEST_BYTES, c.getMaxRequestBytes());
		assertEquals(RESTConfig.DEFAULT_QUERY_MAX_WAIT_MILLIS, c.getQueryMaxWaitMillis());
		assertEquals(PublicQueryService.MAX_QUERY_JUICE, c.getMaxQueryJuice());
		// Query concurrency scales with cores but is always at least one
		assertTrue(c.getMaxConcurrentQueries()>=1, "concurrency must be at least 1");
	}

	@Test
	public void testOverrides() {
		RESTConfig c=RESTConfig.parse("{ \"rest\": { \"maxConcurrentRequests\": 42, \"maxRequestBytes\": 2048,"
				+" \"query\": { \"maxConcurrent\": 7, \"maxWaitMillis\": 250, \"maxJuice\": 5000 } } }");
		assertEquals(42, c.getMaxConcurrentRequests());
		assertEquals(2048, c.getMaxRequestBytes());
		assertEquals(7, c.getMaxConcurrentQueries());
		assertEquals(250, c.getQueryMaxWaitMillis());
		assertEquals(5000, c.getMaxQueryJuice());
	}

	@Test
	public void testNonIntegerValueRejected() {
		RESTConfig c=RESTConfig.parse("{ \"rest\": { \"maxRequestBytes\": \"big\" } }");
		assertThrows(IllegalArgumentException.class, c::getMaxRequestBytes);
	}
}
