package convex.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import convex.core.exceptions.BadFormatException;

@SuppressWarnings("unused")
public class ErrorsTest {
	@Test
	public void testTypes() throws BadFormatException {
		ErrorType[] types = ErrorType.values();

		assertEquals(16, types.length);

		for (ErrorType t : types) {
			assertSame(t, ErrorType.decode(t.getErrorCode()));
		}
	}

	@Test
	public void testBadCode() {
		assertNull(ErrorType.decode(-1));
	}
}
