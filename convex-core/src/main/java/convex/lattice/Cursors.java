package convex.lattice;

import java.util.function.Function;

import convex.core.data.ACell;
import convex.core.lang.RT;

public class Cursors {

	/**
	 * Create a root cursor with the given value
	 * @param <V> Type of cursor value
	 * @param value Any object to be converted to CVM type
	 * @return New cursor instance
	 */
	public static <V extends ACell> Root<V> of(Object value) {
		return Root.create(RT.cvm(value));
	}

	/**
	 * Create a root cursor with the given value
	 * @param <V> Type of cursor value
	 * @param value Any compatible CVM value
	 * @return New cursor instance
	 */
	public static <V extends ACell> Root<V> create(V value) {
		return Root.create(value);
	}

	/**
	 * Creates a cached transformation cursor that combines a TimeCache with a Transformer.
	 * 
	 * This is useful if you have an expensive transformation that you don't want to run repeatedly and a small delay is acceptable.
	 * 
	 * @param <S> The type of values from the source cursor
	 * @param <T> The type of transformed values (must extend ACell)
	 * @param source The source cursor to transform values from
	 * @param transformFunction The function to apply to source values
	 * @param ttl Time-to-live in milliseconds for the cache
	 * @return A cursor that applies the transformation with caching
	 * @throws IllegalArgumentException if ttl is negative (from TimeCache)
	 * @throws NullPointerException if source or transformFunction is null (from Transformer)
	 */
	public static <S extends ACell, T extends ACell> TimeCache<T> cachedTransform(
			ACursor<S> source, 
			Function<S, T> transformFunction, 
			long ttl) {
		// Create a transformer cursor
		Transformer<S, T> transformer = new Transformer<>(source, transformFunction);
		
		// Wrap it with a time cache
		return new TimeCache<>(transformer, ttl);
	}
}
