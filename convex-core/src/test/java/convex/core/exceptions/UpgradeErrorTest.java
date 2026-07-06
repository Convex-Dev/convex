package convex.core.exceptions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import convex.core.data.prim.CVMLong;

/**
 * Tests for UpgradeError classification, which drives the peer's freeze-vs-retry
 * decision. See UPGRADE.md.
 */
public class UpgradeErrorTest {

	@Test
	public void testMissing() {
		UpgradeError e = UpgradeError.missing(3);
		assertEquals(3L, e.getVersion());
		assertNull(e.getCause());
		// A missing migration is deterministic: retrying the same release refails
		assertFalse(e.isRetryable());
	}

	@Test
	public void testFailedDeterministic() {
		UpgradeError e = UpgradeError.failed(4, new IllegalStateException("bug"));
		assertEquals(4L, e.getVersion());
		assertInstanceOf(IllegalStateException.class, e.getCause());
		// A deterministic migration bug is not retryable
		assertFalse(e.isRetryable());
	}

	@Test
	public void testFailedEnvironmental() {
		MissingDataException mde = new MissingDataException(null, CVMLong.create(1).getHash());
		UpgradeError e = UpgradeError.failed(4, mde);
		assertEquals(4L, e.getVersion());
		assertInstanceOf(MissingDataException.class, e.getCause());
		// A peer-local condition may succeed on resync-and-retry: retryable
		assertTrue(e.isRetryable());
	}
}
