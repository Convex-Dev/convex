package convex.lattice.cursor;

import java.util.function.Function;

import convex.core.data.ACell;

/**
 * A cursor that transforms values from a source cursor using an arbitrary function.
 * 
 * This class provides a view over another cursor where each value is transformed
 * by applying a user-provided function. The transformation is applied lazily
 * on each call to {@link #get()}, ensuring that changes to the source cursor
 * are reflected in the transformed values.
 * 
 * <p><strong>Thread Safety:</strong> This implementation is not thread-safe.
 * If concurrent access is required, external synchronization must be provided.
 * 
 * <p><strong>Example usage:</strong>
 * <pre>{@code
 * // Create a source cursor
 * Root<AInteger> source = new Root<>(CVMLong.ONE);
 * 
 * // Create a transform cursor that doubles the value
 * TransformCursor<AInteger, AInteger> doubler = 
 *     new TransformCursor<>(source, value -> value.inc());
 * 
 * // Get transformed value
 * AInteger doubled = doubler.get(); // Returns 2
 * 
 * // Change source value
 * source.set(CVMLong.create(5));
 * AInteger newDoubled = doubler.get(); // Returns 6
 * }</pre>
 * 
 * @param <S> The type of values from the source cursor
 * @param <T> The type of transformed values (must extend ACell)
 * 
 * @see AView
 * @see ACursor
 */
public class Transformer<S extends ACell, T extends ACell> extends AView<T> {

	/**
	 * The transformation function to apply to source values.
	 * Must not be null.
	 */
	private final Function<S, T> transformFunction;

	/**
	 * Creates a new TransformCursor with the specified source cursor and transformation function.
	 * 
	 * @param source The source cursor to transform values from
	 * @param transformFunction The function to apply to source values. Must not be null.
	 * @throws NullPointerException if source or transformFunction is null
	 */
	@SuppressWarnings("unchecked")
	public Transformer(ACursor<S> source, Function<S, T> transformFunction) {
		super((ACursor<T>) source);
		if (transformFunction == null) {
			throw new NullPointerException("Transform function cannot be null");
		}
		this.transformFunction = transformFunction;
	}

	/**
	 * Gets the transformed value by applying the transformation function to the source value.
	 * 
	 * <p>This method fetches the current value from the source cursor and applies
	 * the transformation function. If the source value is null, the transformation
	 * function will be called with null as input.
	 * 
	 * <p>If the transformation function throws an exception, that exception will
	 * be propagated to the caller. It is the responsibility of the transformation
	 * function to handle null values and other edge cases appropriately.
	 * 
	 * @return The transformed value
	 * @throws RuntimeException if the transformation function throws an exception
	 */
	@Override
	@SuppressWarnings("unchecked")
	public T get() {
		S sourceValue = ((ACursor<S>) source).get();
		return transformFunction.apply(sourceValue);
	}

	/**
	 * Gets the transformation function used by this cursor.
	 * 
	 * @return The transformation function
	 */
	public Function<S, T> getTransformFunction() {
		return transformFunction;
	}

	/**
	 * Creates a new TransformCursor with the specified source cursor and transformation function.
	 * 
	 * <p>This is a convenience method that provides a more fluent API for creating
	 * transform cursors. It is equivalent to calling the constructor directly.
	 * 
	 * @param <S> The type of values from the source cursor
	 * @param <T> The type of transformed values
	 * @param source The source cursor to transform values from
	 * @param transformFunction The function to apply to source values
	 * @return A new TransformCursor instance
	 * @throws NullPointerException if source or transformFunction is null
	 */
	public static <S extends ACell, T extends ACell> Transformer<S, T> create(
			ACursor<S> source, 
			Function<S, T> transformFunction) {
		return new Transformer<>(source, transformFunction);
	}

	/**
	 * Creates a TransformCursor that applies multiple transformations in sequence.
	 * 
	 * <p>This method creates a chain of transformations where the output of one
	 * transformation becomes the input of the next. The transformations are
	 * applied in the order they are provided.
	 * 
	 * @param <S> The type of values from the source cursor
	 * @param <T> The type of intermediate values
	 * @param <U> The type of final transformed values
	 * @param source The source cursor to transform values from
	 * @param firstTransform The first transformation function
	 * @param secondTransform The second transformation function
	 * @return A new TransformCursor that applies both transformations
	 * @throws NullPointerException if any parameter is null
	 */
	public static <S extends ACell, T extends ACell, U extends ACell> Transformer<S, U> chain(
			ACursor<S> source,
			Function<S, T> firstTransform,
			Function<T, U> secondTransform) {
		if (firstTransform == null) {
			throw new NullPointerException("First transform function cannot be null");
		}
		if (secondTransform == null) {
			throw new NullPointerException("Second transform function cannot be null");
		}
		
		Function<S, U> combinedTransform = firstTransform.andThen(secondTransform);
		return new Transformer<>(source, combinedTransform);
	}
}
