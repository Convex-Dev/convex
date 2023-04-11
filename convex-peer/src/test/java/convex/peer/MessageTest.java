package convex.peer;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import convex.core.exceptions.BadFormatException;
import convex.net.MessageType;

public class MessageTest {

	@Test
	public void testTypes() throws BadFormatException {
		MessageType[] types = MessageType.values();

		for (MessageType t : types) {
			assertSame(t, MessageType.decode(t.getMessageCode()));
		}
	}

	@Test
	public void testBadCode() {
		assertThrows(BadFormatException.class, () -> MessageType.decode(-1));
	}
}
