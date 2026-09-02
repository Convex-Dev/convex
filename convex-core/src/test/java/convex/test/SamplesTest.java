package convex.test;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import convex.core.exceptions.InvalidDataException;
import convex.core.exceptions.ValidationException;

/**
 * Sanity checks on the shared sample values. Kept separate from {@link Samples}
 * so that the helper class carries no tests and Surefire selects this one by name.
 */
public class SamplesTest {

	@Test
	public void validateDataObjects() throws InvalidDataException, ValidationException {
		Samples.INT_VECTOR_300.validate();
		assertTrue(Samples.INT_VECTOR_300.isCanonical());
		Samples.INT_VECTOR_10.validate();
		assertTrue(Samples.INT_VECTOR_10.isCanonical());
		Samples.BAD_HASH.validate();
	}
}
