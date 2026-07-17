package convex.lattice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.function.BiPredicate;

import org.junit.jupiter.api.Test;

import convex.core.crypto.AKeyPair;
import convex.core.data.ACell;
import convex.core.data.AccountKey;
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

	@Test
	public void testWithMethodsPreserveOtherFields() {
		CVMLong originalTimestamp = CVMLong.create(1000);
		CVMLong replacementTimestamp = CVMLong.create(2000);
		AKeyPair originalKey = AKeyPair.generate();
		AKeyPair replacementKey = AKeyPair.generate();
		BiPredicate<ACell, AccountKey> originalVerifier = (owner, signer) -> true;
		BiPredicate<ACell, AccountKey> replacementVerifier = (owner, signer) -> false;

		LatticeContext original = LatticeContext.create(originalTimestamp, originalKey, originalVerifier);

		LatticeContext withTimestamp = original.withTimestamp(replacementTimestamp);
		assertSame(replacementTimestamp, withTimestamp.getTimestamp());
		assertSame(originalKey, withTimestamp.getSigningKey());
		assertSame(originalVerifier, withTimestamp.getOwnerVerifier());

		LatticeContext withSigningKey = original.withSigningKey(replacementKey);
		assertSame(originalTimestamp, withSigningKey.getTimestamp());
		assertSame(replacementKey, withSigningKey.getSigningKey());
		assertSame(originalVerifier, withSigningKey.getOwnerVerifier());

		LatticeContext withOwnerVerifier = original.withOwnerVerifier(replacementVerifier);
		assertSame(originalTimestamp, withOwnerVerifier.getTimestamp());
		assertSame(originalKey, withOwnerVerifier.getSigningKey());
		assertSame(replacementVerifier, withOwnerVerifier.getOwnerVerifier());

		// Immutable snapshots leave the source context unchanged.
		assertSame(originalTimestamp, original.getTimestamp());
		assertSame(originalKey, original.getSigningKey());
		assertSame(originalVerifier, original.getOwnerVerifier());
	}

	@Test
	public void testWithMethodsCanClearFieldsAndCanonicaliseEmpty() {
		LatticeContext ctx = LatticeContext.create(
			CVMLong.create(1000),
			AKeyPair.generate(),
			(owner, signer) -> true);

		LatticeContext withoutTimestamp = ctx.withTimestamp(null);
		assertNull(withoutTimestamp.getTimestamp());
		assertSame(ctx.getSigningKey(), withoutTimestamp.getSigningKey());
		assertSame(ctx.getOwnerVerifier(), withoutTimestamp.getOwnerVerifier());

		LatticeContext empty = withoutTimestamp
			.withSigningKey(null)
			.withOwnerVerifier(null);
		assertSame(LatticeContext.EMPTY, empty);
	}
}
