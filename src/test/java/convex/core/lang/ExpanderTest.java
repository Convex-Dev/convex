package convex.core.lang;

import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

import convex.core.exceptions.InvalidDataException;
import convex.core.lang.expanders.Expander;

public class ExpanderTest {

	@Test
	public void testValidation() throws InvalidDataException {
		Expander e1 = Expander.wrap(Core.FIRST);
		e1.validate();

		Expander e2 = Expander.wrap(null);
		assertNull(e2);
	}
}
