package convex.gui.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.EventQueue;
import java.awt.event.ActionEvent;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import javax.swing.JButton;

import org.junit.jupiter.api.Test;

import convex.core.Result;
import convex.core.data.prim.CVMLong;
import convex.gui.components.BalanceLabel;

/**
 * Tests for the GUI threading helpers. These need no display.
 */
public class AsyncTest {

	/** Waits until the EDT has run everything posted so far */
	private static void flushEDT() throws Exception {
		EventQueue.invokeAndWait(()->{});
	}

	@Test
	public void testBackground() throws Exception {
		assertFalse(Async.background(EventQueue::isDispatchThread).get());

		IllegalStateException failure=new IllegalStateException("Deliberate test failure");
		ExecutionException e=assertThrows(ExecutionException.class,
			()->Async.background(()->{throw failure;}).get());
		assertEquals(failure, e.getCause());
	}

	@Test
	public void testWhileBusy() throws Exception {
		JButton button=new JButton("Test");
		ActionEvent click=new ActionEvent(button, ActionEvent.ACTION_PERFORMED, "click");

		// Disabled until its work succeeds
		CompletableFuture<String> work=new CompletableFuture<>();
		EventQueue.invokeAndWait(()->Async.whileBusy(click, work));
		assertFalse(button.isEnabled());
		work.complete("done");
		flushEDT();
		assertTrue(button.isEnabled());

		// Re-enabled after a failure too, which is reported rather than thrown
		CompletableFuture<String> failing=new CompletableFuture<>();
		EventQueue.invokeAndWait(()->Async.whileBusy(click, failing));
		assertFalse(button.isEnabled());
		failing.completeExceptionally(new IllegalStateException("Deliberate test failure"));
		flushEDT();
		assertTrue(button.isEnabled());
	}

	@Test
	public void testBalanceLabelFromFutures() throws Exception {
		BalanceLabel label=new BalanceLabel();

		label.show(CompletableFuture.completedFuture(CVMLong.create(5)));
		flushEDT();
		assertEquals(CVMLong.create(5), label.getBalance());

		label.show(CompletableFuture.failedFuture(new IllegalStateException("Deliberate test failure")));
		flushEDT();
		assertNull(label.getBalance());

		label.showResult(CompletableFuture.completedFuture(Result.value(CVMLong.create(7))));
		flushEDT();
		assertEquals(CVMLong.create(7), label.getBalance());
	}
}
