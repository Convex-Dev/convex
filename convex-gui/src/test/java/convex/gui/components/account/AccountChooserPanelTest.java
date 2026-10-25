package convex.gui.components.account;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Test;

import convex.api.ConvexDirect;
import convex.core.crypto.AKeyPair;
import convex.core.cvm.Migrations;
import convex.core.cvm.ops.Constant;
import convex.core.cvm.transactions.Invoke;
import convex.core.init.Init;
import convex.gui.utils.Async;
import convex.gui.utils.Toolkit;

/**
 * Tests for the account chooser. Needs no display.
 */
public class AccountChooserPanelTest {

	/** A sequence number set from the menu is the one the next transaction uses */
	@Test
	public void testSetNextSequence() throws Exception {
		Toolkit.init();
		AKeyPair key=AKeyPair.generate();
		try (ConvexDirect client=ConvexDirect.create(key,
				Migrations.applyAll(Init.createTestState(List.of(key.getAccountKey()))))) {
			AccountChooserPanel chooser=CompletableFuture.supplyAsync(()->new AccountChooserPanel(client), Async.EDT).get();

			assertTrue(chooser.setNextSequence("42"));
			assertEquals(42, nextSequence(client));

			// An invalid entry leaves the sequence as it was: preparing reserved 42, so 43 is next
			assertFalse(chooser.setNextSequence("forty-two"));
			assertEquals(43, nextSequence(client));
		}
	}

	/** Prepares a transaction, returning the sequence number the client gave it */
	private static long nextSequence(ConvexDirect client) throws Exception {
		return client.prepareTransaction(Invoke.create(client.getAddress(), 0, Constant.of(1))).getValue().getSequence();
	}
}
