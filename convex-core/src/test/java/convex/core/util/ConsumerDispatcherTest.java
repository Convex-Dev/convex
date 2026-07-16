package convex.core.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;

class ConsumerDispatcherTest {

	@Test
	void dispatchesInRegistrationOrderAndRemovesByIdentity() {
		ConsumerDispatcher<Integer> dispatcher=new ConsumerDispatcher<>();
		ArrayList<Integer> values=new ArrayList<>();
		Consumer<Integer> first=v->values.add(v);
		Consumer<Integer> second=v->values.add(v*10);

		assertTrue(dispatcher.add(first));
		assertFalse(dispatcher.add(first));
		assertTrue(dispatcher.add(second));
		dispatcher.accept(2);
		assertEquals(java.util.List.of(2,20),values);

		assertTrue(dispatcher.remove(first));
		assertFalse(dispatcher.remove(first));
		dispatcher.accept(3);
		assertEquals(java.util.List.of(2,20,30),values);
	}

	@Test
	void consumerFailureDoesNotPreventRemainingDelivery() {
		ConsumerDispatcher<Integer> dispatcher=new ConsumerDispatcher<>();
		ArrayList<Integer> values=new ArrayList<>();
		dispatcher.add(v->{ throw new IllegalStateException("Expected test failure"); });
		dispatcher.add(values::add);

		dispatcher.accept(7);
		assertEquals(java.util.List.of(7),values);
	}
}
