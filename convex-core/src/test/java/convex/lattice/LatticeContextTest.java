package convex.lattice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

import convex.core.crypto.AKeyPair;
import convex.core.data.prim.CVMLong;

/**
 * Tests for LatticeContext
 */
public class LatticeContextTest {

	@Test
	public void testContextCreation() {
		CVMLong ts = CVMLong.create(12345);
		AKeyPair kp = AKeyPair.generate();

		LatticeContext ctx = LatticeContext.create(ts, kp);

		assertEquals(ts, ctx.getTimestamp());
		assertEquals(kp, ctx.getSigningKey());
	}

	@Test
	public void testEmptyContext() {
		assertNull(LatticeContext.EMPTY.getTimestamp());
		assertNull(LatticeContext.EMPTY.getSigningKey());
	}

	@Test
	public void testCreateWithNullsReturnsEmpty() {
		LatticeContext ctx = LatticeContext.create(null, null);
		assertEquals(LatticeContext.EMPTY, ctx);
	}

	@Test
	public void testContextWithOnlyTimestamp() {
		CVMLong ts = CVMLong.create(99999);
		LatticeContext ctx = LatticeContext.create(ts, null);

		assertEquals(ts, ctx.getTimestamp());
		assertNull(ctx.getSigningKey());
	}

	@Test
	public void testContextWithOnlyKey() {
		AKeyPair kp = AKeyPair.generate();
		LatticeContext ctx = LatticeContext.create(null, kp);

		assertNull(ctx.getTimestamp());
		assertEquals(kp, ctx.getSigningKey());
	}
}
