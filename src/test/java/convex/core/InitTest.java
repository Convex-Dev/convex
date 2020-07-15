package convex.core;

import org.junit.jupiter.api.Test;

import convex.core.exceptions.InvalidDataException;

public class InitTest {

	@Test
	public void testInitState() throws InvalidDataException {
		State s = Init.INITIAL_STATE;
		s.validate();
	}
}
