package convex.lattice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.function.Function;

import org.junit.jupiter.api.Test;

import convex.core.data.prim.CVMLong;
import convex.core.data.prim.AInteger;

/**
 * Tests for Transformer functionality.
 * 
 * These tests verify the transformation behaviour with various functions
 * and edge cases.
 */
public class TransformerTest {

	/**
	 * Test basic transformation functionality.
	 */
	@Test
	public void testBasicTransformation() {
		Root<AInteger> source = Cursors.of(CVMLong.ONE);
		
		// Create a transform cursor that doubles the value
		Transformer<AInteger, AInteger> doubler = 
			new Transformer<>(source, value -> value.inc());
		
		// Test transformation
		AInteger result = doubler.get();
		assertEquals(CVMLong.create(2), result);
		
		// Change source and test again
		source.set(CVMLong.create(5));
		AInteger newResult = doubler.get();
		assertEquals(CVMLong.create(6), newResult);
	}

	/**
	 * Test transformation with null values.
	 */
	@Test
	public void testNullValueTransformation() {
		Root<AInteger> source = Cursors.of((AInteger)null);
		
		// Transform that handles null by returning a default value
		Transformer<AInteger, AInteger> nullHandler = 
			new Transformer<>(source, value -> value != null ? value.inc() : CVMLong.ZERO);
		
		AInteger result = nullHandler.get();
		assertEquals(CVMLong.ZERO, result);
		
		// Set a value and test
		source.set(CVMLong.ONE);
		AInteger newResult = nullHandler.get();
		assertEquals(CVMLong.create(2), newResult);
	}

	/**
	 * Test constructor validation.
	 */
	@Test
	public void testConstructorValidation() {
		Root<AInteger> source = Cursors.of(CVMLong.ONE);
		
		// Valid constructor should work
		Transformer<AInteger, AInteger> valid = 
			new Transformer<>(source, value -> value.inc());
		assertNotNull(valid);
		
		// Null source should be handled by parent constructor
		// (This might throw or be handled by AView constructor)
		
		// Null transform function should throw
		assertThrows(NullPointerException.class, () -> {
			new Transformer<>(source, null);
		});
	}

	/**
	 * Test static create method.
	 */
	@Test
	public void testStaticCreate() {
		Root<AInteger> source = Cursors.of(CVMLong.ONE);
		
		Transformer<AInteger, AInteger> cursor = 
			Transformer.create(source, value -> value.inc());
		
		assertNotNull(cursor);
		assertEquals(CVMLong.create(2), cursor.get());
	}

	/**
	 * Test chained transformations.
	 */
	@Test
	public void testChainedTransformations() {
		Root<AInteger> source = Cursors.of(CVMLong.ONE);
		
		// Chain: increment, then multiply by 2
		Transformer<AInteger, AInteger> chained = Transformer.chain(
			source,
			(AInteger value) -> value.inc(),           // 1 -> 2
			(AInteger value) -> value.add(value)       // 2 -> 4
		);
		
		AInteger result = chained.get();
		assertEquals(CVMLong.create(4), result);
		
		// Test with different source value
		source.set(CVMLong.create(3));
		AInteger newResult = chained.get();
		assertEquals(CVMLong.create(8), newResult); // (3+1)*2 = 8
	}


	/**
	 * Test transformation with identity function.
	 */
	@Test
	public void testIdentityTransformation() {
		Root<AInteger> source = Cursors.of(CVMLong.ONE);
		
		Transformer<AInteger, AInteger> identity = 
			new Transformer<>(source, value -> value);
		
		AInteger result = identity.get();
		assertEquals(CVMLong.ONE, result);
		assertSame(CVMLong.ONE, result); // Should be same instance
	}

	/**
	 * Test transformation that changes type.
	 */
	@Test
	public void testTypeChangingTransformation() {
		Root<AInteger> source = Cursors.of(CVMLong.ONE);
		
		// Transform integer to string representation
		Transformer<AInteger, AInteger> toString = 
			new Transformer<>(source, value -> CVMLong.create(value.toString().length()));
		
		AInteger result = toString.get();
		assertEquals(CVMLong.ONE, result); // "1" has length 1
		
		// Test with larger number
		source.set(CVMLong.create(123));
		AInteger newResult = toString.get();
		assertEquals(CVMLong.create(3), newResult); // "123" has length 3
	}

	/**
	 * Test getTransformFunction method.
	 */
	@Test
	public void testGetTransformFunction() {
		Root<AInteger> source = Cursors.of(CVMLong.ONE);
		Function<AInteger, AInteger> transform = value -> value.inc();
		
		Transformer<AInteger, AInteger> cursor = 
			new Transformer<>(source, transform);
		
		assertSame(transform, cursor.getTransformFunction());
	}


	/**
	 * Test chained transformations with null handling.
	 */
	@Test
	public void testChainedTransformationsWithNull() {
		Root<AInteger> source = Cursors.of((AInteger)null);
		
		// Chain that handles null values
		Transformer<AInteger, AInteger> chained = Transformer.chain(
			source,
			(AInteger value) -> value != null ? value : CVMLong.ZERO,
			(AInteger value) -> value.inc()
		);
		
		AInteger result = chained.get();
		assertEquals(CVMLong.ONE, result); // null -> 0 -> 1
		
		// Set a value and test
		source.set(CVMLong.create(5));
		AInteger newResult = chained.get();
		assertEquals(CVMLong.create(6), newResult); // 5 -> 6
	}


	/**
	 * Test static method validation.
	 */
	@Test
	public void testStaticMethodValidation() {
		Root<AInteger> source = Cursors.of(CVMLong.ONE);
		
		// Test chain method with null parameters
		assertThrows(NullPointerException.class, () -> {
			Transformer.chain(source, null, (AInteger value) -> value.inc());
		});
		
		assertThrows(NullPointerException.class, () -> {
			Transformer.chain(source, (AInteger value) -> value.inc(), null);
		});
		
	}

	/**
	 * Test complex transformation chain.
	 */
	@Test
	public void testComplexTransformationChain() {
		Root<AInteger> source = Cursors.of(CVMLong.ONE);
		
		// Complex chain: increment, then multiply by 2
		Transformer<AInteger, AInteger> complex = Transformer.chain(
			source,
			(AInteger value) -> value.inc(),                    // 1 -> 2
			(AInteger value) -> value.add(value)                // 2 -> 4
		);
		
		AInteger result = complex.get();
		assertEquals(CVMLong.create(4), result);
		
		// Test with different source value
		source.set(CVMLong.create(3));
		AInteger newResult = complex.get();
		assertEquals(CVMLong.create(8), newResult); // (3+1)*2 = 8
	}
}
