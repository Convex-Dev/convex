package convex.restapi.api;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import io.javalin.http.BadRequestResponse;

/**
 * Unit tests for {@link ABaseAPI#paginationRange} — the bounded pagination logic (#660).
 * Pure and deterministic: no server, no HTTP.
 */
public class PaginationRangeTest {

	@Test
	public void testDefaults() {
		// No params: offset 0, default limit
		assertArrayEquals(new long[] {0, 10, 10}, ABaseAPI.paginationRange(null, null, 1000));
	}

	@Test
	public void testLimitHonouredWithoutOffset() {
		// #660: limit must be honoured even when offset is absent (the old code ignored it)
		assertEquals(25, ABaseAPI.paginationRange(null, "25", 1000)[2]);
	}

	@Test
	public void testOffsetWithoutLimitUsesDefault() {
		// #660: offset present, limit absent must use the default — the old code tested
		// offsetParam and so called parse(null) here, failing the request
		long[] r = ABaseAPI.paginationRange("5", null, 1000);
		assertEquals(5, r[0]);
		assertEquals(10, r[2]);
	}

	@Test
	public void testLimitClampedToMax() {
		// #660: an oversized limit is clamped, bounding the read regardless of the request
		long[] r = ABaseAPI.paginationRange("0", "1000000000", 10_000_000);
		assertEquals(ABaseAPI.MAX_LIMIT, r[2]);
		assertEquals(ABaseAPI.MAX_LIMIT, r[1]);
	}

	@Test
	public void testEndClampedToMaxIndex() {
		// end never exceeds maxIndex, even when offset+limit would
		assertEquals(30, ABaseAPI.paginationRange("0", "500", 30)[1]);
	}

	@Test
	public void testNegativeAndOutOfRangeRejected() {
		assertThrows(BadRequestResponse.class, () -> ABaseAPI.paginationRange("-1", null, 100));
		assertThrows(BadRequestResponse.class, () -> ABaseAPI.paginationRange(null, "-5", 100));
		assertThrows(BadRequestResponse.class, () -> ABaseAPI.paginationRange("101", null, 100));
	}
}
