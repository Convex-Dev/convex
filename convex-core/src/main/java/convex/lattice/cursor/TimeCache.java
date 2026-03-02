package convex.lattice.cursor;

import convex.core.data.ACell;
import convex.core.util.Utils;

/**
 * A time-based cache implementation for cursor values.
 * 
 * This class provides a caching layer over an underlying cursor that automatically
 * refreshes the cached value when it expires based on a configurable time-to-live (TTL).
 * The cache is designed to reduce expensive operations by serving cached values
 * until they become stale.
 * 
 * <p>The cache uses millisecond timestamps for TTL calculations, where the TTL
 * represents the maximum age (in milliseconds) that a cached value can be before
 * it's considered expired and needs to be refreshed from the source cursor.
 * 
 * <p><strong>Example usage:</strong>
 * <pre>{@code
 * // Create a time cache with 5 second TTL
 * TimeCache<SomeValue> cache = new TimeCache<>(sourceCursor, 5000);
 * 
 * // First call fetches from source and caches the result
 * SomeValue value1 = cache.get(); // Fetches from source
 * 
 * // Subsequent calls within TTL return cached value
 * SomeValue value2 = cache.get(); // Returns cached value
 * 
 * // After TTL expires, next call fetches fresh value
 * Thread.sleep(6000);
 * SomeValue value3 = cache.get(); // Fetches from source again
 * }</pre>
 * 
 * @param <V> The type of value being cached, must extend ACell
 * 
 * @see ACachedView
 * @see ACursor
 */
public class TimeCache<V extends ACell> extends ACachedView<V> {

	/**
	 * Time-to-live in milliseconds. Values older than this will be refreshed.
	 * Must be non-negative.
	 */
	private final long ttl;
	
	/**
	 * Timestamp of the last cache update in milliseconds since epoch.
	 * A value of -1 indicates the cache has never been populated.
	 */
	private volatile long lastUpdate = -1;
	
	/**
	 * The currently cached value. May be null if the source cursor returns null.
	 */
	private volatile V value;

	/**
	 * Creates a new TimeCache with the specified source cursor and TTL.
	 * 
	 * @param source The underlying cursor to cache values from
	 * @param timeToLive Time-to-live in milliseconds. Must be non-negative.
	 * @throws IllegalArgumentException if timeToLive is negative
	 * @throws NullPointerException if source is null
	 */
	protected TimeCache(ACursor<V> source, long timeToLive) {
		super(source);
		if (timeToLive < 0) {
			throw new IllegalArgumentException("Time-to-live must be non-negative, got: " + timeToLive);
		}
		this.ttl = timeToLive;
	}
	
	/**
	 * Gets the cached value, refreshing it if necessary.
	 * 
	 * <p>If the cache is empty or the cached value has expired (based on TTL),
	 * this method will fetch a fresh value from the source cursor and update
	 * the cache. Otherwise, it returns the cached value.
	 * 
	 * <p>The method uses the current system timestamp to determine if the
	 * cached value has expired. The timestamp is obtained using
	 * {@link Utils#getCurrentTimestamp()}.
	 * 
	 * @return The current value, either from cache or freshly fetched from source
	 */
	@Override
	public V get() {
		long currentTime = Utils.getCurrentTimestamp();
		
		// Check if we have a valid cached value that hasn't expired
		if (lastUpdate >= 0) {
			boolean isValid = false;
			
			// Check for overflow in the addition
			long expirationTime = lastUpdate + ttl;
			if (expirationTime < lastUpdate) {
				// Overflow occurred, cache effectively never expires
				isValid = true;
			} else {
				isValid = currentTime < expirationTime;
			}
			
			if (isValid) {
				return value;
			}
		}
		
		// Cache is empty or expired, fetch fresh value from source
		V freshValue = source.get();
		
		// Update cache 
		this.value = freshValue;
		this.lastUpdate = currentTime;
		
		return freshValue;
	}

	/**
	 * Gets the time-to-live value for this cache.
	 * 
	 * @return The TTL in milliseconds
	 */
	public long getTTL() {
		return ttl;
	}
	
	/**
	 * Gets the timestamp of the last cache update.
	 * 
	 * @return The last update timestamp in milliseconds since epoch, or -1 if never updated
	 */
	public long getLastUpdateTime() {
		return lastUpdate;
	}
	
	/**
	 * Checks if the cache currently has a valid (non-expired) value.
	 * 
	 * @return true if the cache has a valid value, false otherwise
	 */
	public boolean hasValidCache() {
		if (lastUpdate < 0) {
			return false; // Never been updated
		}
		long currentTime = Utils.getCurrentTimestamp();
		
		// Handle overflow case when TTL is very large (e.g., Long.MAX_VALUE)
		if (ttl == Long.MAX_VALUE) {
			return true; // Never expires
		}
		
		// Check for overflow in the addition
		long expirationTime = lastUpdate + ttl;
		if (expirationTime < lastUpdate) {
			// Overflow occurred, cache effectively never expires
			return true;
		}
		
		return currentTime < expirationTime;
	}
	
	/**
	 * Forces a cache refresh by clearing the current cached value.
	 * The next call to {@link #get()} will fetch a fresh value from the source.
	 */
	public void invalidate() {
		this.lastUpdate = -1;
		this.value = null;
	}

}
