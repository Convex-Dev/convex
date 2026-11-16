package convex.core.exceptions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

import convex.core.data.prim.CVMLong;

/**
 * Tests that UpgradeError keeps the version and the cause, which tells the operator
 * of a halted peer how to recover it. See UPGRADE.md.
 */
public class UpgradeErrorTest {

	@Test
	public void testMissing() {
		UpgradeError e = UpgradeError.missing(3);
		assertEquals(3L, e.getVersion());
		// A missing migration has no cause: a release update is required
		assertNull(e.getCause());
	}

	@Test
	public void testFailedDeterministic() {
		UpgradeError e = UpgradeError.failed(4, new IllegalStateException("bug"));
		assertEquals(4L, e.getVersion());
		assertInstanceOf(IllegalStateException.class, e.getCause());
	}

	@Test
	public void testFailedEnvironmental() {
		MissingDataException mde = new MissingDataException(null, CVMLong.create(1).getHash());
		UpgradeError e = UpgradeError.failed(4, mde);
		assertEquals(4L, e.getVersion());
		assertInstanceOf(MissingDataException.class, e.getCause());
	}
}
