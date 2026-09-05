package convex.core.message;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;

public class LocalConnectionTest {

	@Test
	public void testCloseClosesAndUnlinksBothEnds() {
		AtomicBoolean delivered=new AtomicBoolean();
		LocalConnection first=LocalConnection.createPair(message -> true, message -> {
			delivered.set(true);
			return true;
		});
		LocalConnection second=first.getPaired();

		assertTrue(first.sendMessage(Message.createPing(1)));
		assertTrue(delivered.get());

		first.close();
		first.close();

		assertTrue(first.isClosed());
		assertTrue(second.isClosed());
		assertNull(first.getPaired());
		assertNull(second.getPaired());
		assertFalse(first.sendMessage(Message.createPing(2)));
		assertFalse(second.returnMessage(Message.createPing(3)));
	}
}
