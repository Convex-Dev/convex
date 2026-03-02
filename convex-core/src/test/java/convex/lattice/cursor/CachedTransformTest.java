package convex.lattice.cursor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.function.Function;

import org.junit.jupiter.api.Test;

import convex.core.data.prim.AInteger;
import convex.core.data.prim.CVMLong;

/**
 * Tests for Cursors.cachedTransform functionality.
 * 
 * These tests verify the combined caching and transformation behavior
 * without using sleep by manipulating source values and testing cache invalidation.
 */
public class CachedTransformTest {

	/**
	 * Test basic cached transformation functionality.
	 */
	@Test
	public void testBasicCachedTransform() {
		Root<AInteger> source = Cursors.of(1);
		
		// Create a cached transformation that doubles the value
		ACursor<AInteger> cachedDoubler = Cursors.cachedTransform(
			source, 
			value -> value.inc(), 
			1000000000
		);
		
		// First call should compute and cache
		AInteger result1 = cachedDoubler.get();
		assertEquals(CVMLong.create(2), result1);

        source.set(CVMLong.create(666)); // evil value shouldn't get picked up yet
		
		// Second call should return cached value
		AInteger result2 = cachedDoubler.get();
		assertSame(result1, result2); // Should be same instance (cached)
	}

	/**
	 * Test cached transformation with source value changes.
	 */
	@Test
	public void testCachedTransformWithSourceChanges() {
		Root<AInteger> source = Cursors.of(1);
		
		ACursor<AInteger> cachedDoubler = Cursors.cachedTransform(
			source, 
			value -> value.inc(), 
			1000
		);
		
		// Initial call
		AInteger result1 = cachedDoubler.get();
		assertEquals(CVMLong.create(2), result1);
		
		// Change source value
		source.set(CVMLong.create(5));
		
		// Should still return cached value (not expired)
		AInteger result2 = cachedDoubler.get();
		assertSame(result1, result2);
		assertEquals(CVMLong.create(2), result2);
		
		// Invalidate cache and test again
		if (cachedDoubler instanceof TimeCache) {
			((TimeCache<AInteger>) cachedDoubler).invalidate();
			AInteger result3 = cachedDoubler.get();
			assertEquals(CVMLong.create(6), result3); // 5 + 1 = 6
		}
	}

	/**
	 * Test cached transformation with zero TTL (always recompute).
	 */
	@Test
	public void testCachedTransformWithZeroTTL() {
		Root<AInteger> source = Cursors.of(1);
		
		ACursor<AInteger> cachedDoubler = Cursors.cachedTransform(
			source, 
			value -> value.inc(), 
			0
		);
		
		// First call
		AInteger result1 = cachedDoubler.get();
		assertEquals(CVMLong.create(2), result1);
		
		// Change source
		source.set(CVMLong.create(3));
		
		// With zero TTL, should always recompute
		AInteger result2 = cachedDoubler.get();
		assertEquals(CVMLong.create(4), result2);
		assertNotSame(result1, result2);
	}

	/**
	 * Test cached transformation with null values.
	 */
	@Test
	public void testCachedTransformWithNullValues() {
		Root<AInteger> source = Cursors.of((AInteger)null);
		
		ACursor<AInteger> cachedHandler = Cursors.cachedTransform(
			source, 
			value -> value != null ? value.inc() : CVMLong.ZERO, 
			1000
		);
		
		// Should handle null values correctly
		AInteger result1 = cachedHandler.get();
		assertEquals(CVMLong.ZERO, result1);
		
		// Second call should return cached value
		AInteger result2 = cachedHandler.get();
		assertSame(result1, result2);
		
		// Set a value and invalidate cache
		source.set(CVMLong.ONE);
		if (cachedHandler instanceof TimeCache) {
			((TimeCache<AInteger>) cachedHandler).invalidate();
			AInteger result3 = cachedHandler.get();
			assertEquals(CVMLong.create(2), result3);
		}
	}

	/**
	 * Test cached transformation with complex function.
	 */
	@Test
	public void testCachedTransformWithComplexFunction() {
		Root<AInteger> source = Cursors.of(1);
		
		// Complex transformation: square the value
		ACursor<AInteger> cachedSquarer = Cursors.cachedTransform(
			source, 
			value -> (AInteger) value.multiply(value), 
			1000
		);
		
		// First call
		AInteger result1 = cachedSquarer.get();
		assertEquals(CVMLong.ONE, result1); // 1 * 1 = 1
		
		// Second call should return cached value
		AInteger result2 = cachedSquarer.get();
		assertSame(result1, result2);
		
		// Change source and invalidate cache
		source.set(CVMLong.create(3));
		if (cachedSquarer instanceof TimeCache) {
			((TimeCache<AInteger>) cachedSquarer).invalidate();
			AInteger result3 = cachedSquarer.get();
			assertEquals(CVMLong.create(9), result3); // 3 * 3 = 9
		}
	}

	/**
	 * Test cached transformation with type changing function.
	 */
	@Test
	public void testCachedTransformWithTypeChange() {
		Root<AInteger> source = Cursors.of(123);
		
		// Transform to string length
		ACursor<AInteger> cachedLength = Cursors.cachedTransform(
			source, 
			value -> CVMLong.create(value.toString().length()), 
			1000
		);
		
		// First call
		AInteger result1 = cachedLength.get();
		assertEquals(CVMLong.create(3), result1); // "123" has length 3
		
		// Second call should return cached value
		AInteger result2 = cachedLength.get();
		assertSame(result1, result2);
	}

	/**
	 * Test basic functionality with valid parameters.
	 */
	@Test
	public void testBasicFunctionality() {
		Root<AInteger> source = Cursors.of(1);
		Function<AInteger, AInteger> transform = value -> value.inc();
		
		// Valid parameters should work
		ACursor<AInteger> valid = Cursors.cachedTransform(source, transform, 1000);
		assertNotNull(valid);
		assertEquals(CVMLong.create(2), valid.get());
	}

	/**
	 * Test cached transformation with very large TTL.
	 */
	@Test
	public void testCachedTransformWithLargeTTL() {
		Root<AInteger> source = Cursors.of(1);
		
		ACursor<AInteger> cachedDoubler = Cursors.cachedTransform(
			source, 
			value -> value.inc(), 
			Long.MAX_VALUE
		);
		
		// First call
		AInteger result1 = cachedDoubler.get();
		assertEquals(CVMLong.create(2), result1);
		
		// Change source
		source.set(CVMLong.create(5));
		
		// Should still return cached value (effectively never expires)
		AInteger result2 = cachedDoubler.get();
		assertSame(result1, result2);
		assertEquals(CVMLong.create(2), result2);
	}

	/**
	 * Test cached transformation with identity function.
	 */
	@Test
	public void testCachedTransformWithIdentity() {
		Root<AInteger> source = Cursors.of(1);
		
		ACursor<AInteger> cachedIdentity = Cursors.cachedTransform(
			source, 
			value -> value, 
			1000
		);
		
		// First call
		AInteger result1 = cachedIdentity.get();
		assertEquals(CVMLong.ONE, result1);
		assertSame(CVMLong.ONE, result1); // Should be same instance
		
		// Second call should return cached value
		AInteger result2 = cachedIdentity.get();
		assertSame(result1, result2);
	}

	/**
	 * Test cached transformation with multiple rapid calls.
	 */
	@Test
	public void testCachedTransformRapidCalls() {
		Root<AInteger> source = Cursors.of(1);
		
		ACursor<AInteger> cachedDoubler = Cursors.cachedTransform(
			source, 
			value -> value.inc(), 
			1000
		);
		
		// Multiple rapid calls should all return same cached value
		AInteger result1 = cachedDoubler.get();
		AInteger result2 = cachedDoubler.get();
		AInteger result3 = cachedDoubler.get();
		
		assertSame(result1, result2);
		assertSame(result2, result3);
		assertEquals(CVMLong.create(2), result1);
	}

	/**
	 * Test cached transformation with different TTL values.
	 */
	@Test
	public void testCachedTransformDifferentTTL() {
		Root<AInteger> source = Cursors.of(1);
		
		// Test various TTL values
		ACursor<AInteger> cache1 = Cursors.cachedTransform(source, value -> value.inc(), 0);
		ACursor<AInteger> cache2 = Cursors.cachedTransform(source, value -> value.inc(), 1);
		ACursor<AInteger> cache3 = Cursors.cachedTransform(source, value -> value.inc(), 1000000);
		
		// All should work correctly
		assertEquals(CVMLong.create(2), cache1.get());
		assertEquals(CVMLong.create(2), cache2.get());
		assertEquals(CVMLong.create(2), cache3.get());
	}
}
