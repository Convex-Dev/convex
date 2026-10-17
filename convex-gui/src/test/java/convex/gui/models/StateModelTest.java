package convex.gui.models;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.EventQueue;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;

import convex.gui.utils.Async;

/**
 * Tests for observable state values. These need no display.
 */
public class StateModelTest {

	@Test
	public void testUpdatesFromAnyThread() throws Exception {
		StateModel<Integer> model=StateModel.create(0);
		List<Integer> seen=Collections.synchronizedList(new ArrayList<>());
		List<Boolean> wellFormed=Collections.synchronizedList(new ArrayList<>());
		model.addPropertyChangeListener(e->{
			seen.add((Integer)e.getNewValue());
			wellFormed.add(EventQueue.isDispatchThread()&&(e.getSource()==model));
		});

		// A burst of updates from another thread
		Async.background(()->{
			for (int i=1; i<=100; i++) model.setValue(i);
			return null;
		}).get();
		EventQueue.invokeAndWait(()->{});

		// Listeners hear about the latest value, on the EDT, from the model
		assertEquals(100, model.getValue());
		assertEquals(100, seen.get(seen.size()-1));
		assertTrue(seen.size()<=100);
		assertTrue(wellFormed.stream().allMatch(b->b));

		// Setting an equal value fires nothing
		int events=seen.size();
		model.setValue(100);
		EventQueue.invokeAndWait(()->{});
		assertEquals(events, seen.size());
	}
}
