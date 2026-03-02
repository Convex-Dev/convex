package convex.lattice.cursor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import convex.core.data.prim.AInteger;
import convex.core.data.prim.CVMLong;

/**
 * Tests for TimeCache functionality.
 * 
 * These tests verify the caching behavior without using sleep by manipulating
 * the source cursor values and testing cache invalidation logic.
 */
public class CacheTest {

	/**
	 * Test basic caching functionality with immediate cache hits.
	 */
	@Test
	public void testBasicCaching() {
		// Create a source cursor with initial value
		Root<AInteger> source = Cursors.of(CVMLong.ONE);
		
		// Create TimeCache with 1000ms TTL
		TimeCache<AInteger> cache = new TimeCache<>(source, 1000);
		
		// First call should fetch from source and cache
		AInteger value1 = cache.get();
		assertEquals(CVMLong.ONE, value1);
		assertTrue(cache.hasValidCache());
		
		// Second call should return cached value
		AInteger value2 = cache.get();
		assertSame(value1, value2); // Should be same instance (cached)
		assertTrue(cache.hasValidCache());
	}

	/**
	 * Test cache invalidation and refresh.
	 */
	@Test
	public void testCacheInvalidation() {
		Root<AInteger> source = Cursors.of(CVMLong.ONE);
		TimeCache<AInteger> cache = new TimeCache<>(source, 1000);
		
		// Initial fetch
		AInteger value1 = cache.get();
		assertEquals(CVMLong.ONE, value1);
		assertTrue(cache.hasValidCache());
		
		// Manually invalidate cache
		cache.invalidate();
		assertFalse(cache.hasValidCache());
		assertEquals(-1, cache.getLastUpdateTime());
		
		// Change source value
		source.set(CVMLong.create(2));
		
		// Next call should fetch fresh value
		AInteger value2 = cache.get();
		assertEquals(CVMLong.create(2), value2);
		assertTrue(cache.hasValidCache());
		assertNotSame(value1, value2);
	}

	/**
	 * Test cache behavior with source value changes.
	 */
	@Test
	public void testSourceValueChanges() {
		Root<AInteger> source = Cursors.of(CVMLong.ONE);
		TimeCache<AInteger> cache = new TimeCache<>(source, 1000);
		
		// Initial fetch
		AInteger value1 = cache.get();
		assertEquals(CVMLong.ONE, value1);
		
		// Change source value
		source.set(CVMLong.create(2));
		
		// Cache should still return old value (not expired yet)
		AInteger value2 = cache.get();
		assertSame(value1, value2); // Should still be cached
		assertEquals(CVMLong.ONE, value2);
		
		// Invalidate and fetch again
		cache.invalidate();
		AInteger value3 = cache.get();
		assertEquals(CVMLong.create(2), value3);
		assertNotSame(value1, value3);
	}

	/**
	 * Test cache with zero TTL (always refresh).
	 */
	@Test
	public void testZeroTTL() {
		Root<AInteger> source = Cursors.of(CVMLong.ONE);
		TimeCache<AInteger> cache = new TimeCache<>(source, 0);
		
		// First call
		AInteger value1 = cache.get();
		assertEquals(CVMLong.ONE, value1);
		
		// Change source
		source.set(CVMLong.create(2));
		
		// With zero TTL, should always fetch fresh
		AInteger value2 = cache.get();
		assertEquals(CVMLong.create(2), value2);
		assertNotSame(value1, value2);
	}

	/**
	 * Test cache with null values.
	 */
	@Test
	public void testNullValues() {
		Root<AInteger> source = new Root<AInteger>((AInteger)null);
		TimeCache<AInteger> cache = new TimeCache<>(source, 1000);
		
		// Should handle null values correctly
		AInteger value1 = cache.get();
		assertEquals(null, value1);
		assertTrue(cache.hasValidCache());
		
		// Second call should return same null
		AInteger value2 = cache.get();
		assertSame(value1, value2);
	}

	/**
	 * Test constructor validation.
	 */
	@Test
	public void testConstructorValidation() {
		Root<AInteger> source = new Root<AInteger>(CVMLong.ONE);
		
		// Valid TTL should work
		TimeCache<AInteger> cache1 = new TimeCache<>(source, 0);
		assertNotNull(cache1);
		
		TimeCache<AInteger> cache2 = new TimeCache<>(source, 1000);
		assertNotNull(cache2);
		
		// Negative TTL should throw exception
		assertThrows(IllegalArgumentException.class, () -> {
			new TimeCache<>(source, -1);
		});
		
		assertThrows(IllegalArgumentException.class, () -> {
			new TimeCache<>(source, -100);
		});
	}

	/**
	 * Test utility methods.
	 */
	@Test
	public void testUtilityMethods() {
		Root<AInteger> source = Cursors.of(CVMLong.ONE);
		TimeCache<AInteger> cache = new TimeCache<>(source, 5000);
		
		// Test TTL getter
		assertEquals(5000, cache.getTTL());
		
		// Test initial state
		assertEquals(-1, cache.getLastUpdateTime());
		assertFalse(cache.hasValidCache());
		
		// After first get, should have valid cache
		cache.get();
		assertTrue(cache.getLastUpdateTime() > 0);
		assertTrue(cache.hasValidCache());
	}

	/**
	 * Test cache behavior with multiple rapid calls.
	 */
	@Test
	public void testRapidCalls() {
		Root<AInteger> source = Cursors.of(1);
		TimeCache<AInteger> cache = new TimeCache<>(source, 1000);
		
		// Multiple rapid calls should all return same cached value
		AInteger value1 = cache.get();
		AInteger value2 = cache.get();
		AInteger value3 = cache.get();
		
		assertSame(value1, value2);
		assertSame(value2, value3);
		assertEquals(CVMLong.ONE, value1);
		assertTrue(cache.hasValidCache());
	}

	/**
	 * Test cache with different TTL values.
	 */
	@Test
	public void testDifferentTTLValues() {
		Root<AInteger> source = Cursors.of(1);
		
		// Test various TTL values
		TimeCache<AInteger> cache1 = new TimeCache<>(source, 0);
		TimeCache<AInteger> cache2 = new TimeCache<>(source, 1);
		TimeCache<AInteger> cache3 = new TimeCache<>(source, 1000000);
		
		assertEquals(0, cache1.getTTL());
		assertEquals(1, cache2.getTTL());
		assertEquals(1000000, cache3.getTTL());
		
		// All should work correctly
		assertEquals(CVMLong.ONE, cache1.get());
		assertEquals(CVMLong.ONE, cache2.get());
		assertEquals(CVMLong.ONE, cache3.get());
	}

	/**
	 * Test cache behavior with source that changes frequently.
	 */
	@Test
	public void testFrequentSourceChanges() {
		Root<AInteger> source = Cursors.of(1);
		TimeCache<AInteger> cache = new TimeCache<>(source, 1000);
		
		// Initial fetch
		AInteger value1 = cache.get();
		assertEquals(CVMLong.ONE, value1);
		
		// Change source multiple times
		source.set(CVMLong.create(2));
		source.set(CVMLong.create(3));
		source.set(CVMLong.create(4));
		
		// Cache should still return original value
		AInteger value2 = cache.get();
		assertSame(value1, value2);
		assertEquals(CVMLong.ONE, value2);
		
		// After invalidation, should get latest value
		cache.invalidate();
		AInteger value3 = cache.get();
		assertEquals(CVMLong.create(4), value3);
	}

	/**
	 * Test cache with very large TTL values.
	 */
	@Test
	public void testLargeTTLValues() {
		Root<AInteger> source = Cursors.of(1);
		TimeCache<AInteger> cache = new TimeCache<>(source, Long.MAX_VALUE);
		
		assertEquals(Long.MAX_VALUE, cache.getTTL());
		
		// Should work normally
		AInteger value1 = cache.get();
		assertEquals(CVMLong.ONE, value1);
		assertTrue(cache.hasValidCache());
		
		// Change source
		source.set(CVMLong.create(2));
		
		// Should still return cached value (effectively never expires)
		AInteger value2 = cache.get();
		assertSame(value1, value2);
		assertEquals(CVMLong.ONE, value2);
	}
}
