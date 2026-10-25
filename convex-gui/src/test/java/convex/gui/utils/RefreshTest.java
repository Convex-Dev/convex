package convex.gui.utils;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.EventQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import javax.swing.JPanel;

import org.junit.jupiter.api.Test;

/**
 * Tests for refreshing components while they show. These need no display:
 * the tests switch showing on and off as the component's hierarchy would.
 */
public class RefreshTest {

	private static boolean isActive(Refresh refresh) throws Exception {
		boolean[] active=new boolean[1];
		EventQueue.invokeAndWait(()->active[0]=refresh.isActive());
		return active[0];
	}

	@Test
	public void testRunsOnlyWhileShowing() throws Exception {
		CountDownLatch ran=new CountDownLatch(1);
		Refresh refresh=Refresh.whileShowing(new JPanel(), 10, ran::countDown);

		// A component that isn't showing isn't refreshed
		assertFalse(isActive(refresh));

		// Showing starts refreshing straight away
		EventQueue.invokeAndWait(()->refresh.showing(true));
		assertTrue(isActive(refresh));
		assertTrue(ran.await(10, TimeUnit.SECONDS));

		// Hiding stops it
		EventQueue.invokeAndWait(()->refresh.showing(false));
		assertFalse(isActive(refresh));
	}

	@Test
	public void testFailuresDontStopRefreshing() throws Exception {
		CountDownLatch ranTwice=new CountDownLatch(2);
		Refresh refresh=Refresh.whileShowing(new JPanel(), 10, ()->{
			ranTwice.countDown();
			throw new IllegalStateException("Deliberate test failure");
		});

		EventQueue.invokeAndWait(()->refresh.showing(true));
		assertTrue(ranTwice.await(10, TimeUnit.SECONDS));
		EventQueue.invokeAndWait(()->refresh.showing(false));
	}
}
