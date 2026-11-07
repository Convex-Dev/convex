package convex.lattice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.function.BiPredicate;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import convex.auth.did.DID;
import convex.core.crypto.AKeyPair;
import convex.core.cvm.Address;
import convex.core.data.ACell;
import convex.core.data.AccountKey;
import convex.core.data.SignedData;
import convex.core.data.Strings;
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
	public void testDefaultOwnerRuleAcceptsOnlyKeyOwners() {
		AKeyPair owner = AKeyPair.createSeeded(7821);
		AKeyPair other = AKeyPair.createSeeded(7822);
		AccountKey key = owner.getAccountKey();
		for (ACell direct : new ACell[] {key, DID.forKey(key), key.toFlatBlob()}) {
			assertTrue(LatticeContext.EMPTY.verifyOwner(direct, key));
			assertFalse(LatticeContext.EMPTY.verifyOwner(direct, other.getAccountKey()));
		}
		for (ACell indirect : new ACell[] {Address.create(1337), Strings.create("did:web:example.com"),
				Strings.create("did:convex:1337"), Strings.create("anyone")}) {
			assertFalse(LatticeContext.EMPTY.verifyOwner(indirect, key), "unverified owners must fail closed");
			assertNull(LatticeContext.create(null, owner).signAs(indirect, CVMLong.ONE));
		}
	}

	@Test
	public void testOwnerVerifierDecidesForEveryOwner() {
		AKeyPair owner = AKeyPair.createSeeded(7823);
		AKeyPair delegate = AKeyPair.createSeeded(7824);
		AccountKey key = owner.getAccountKey();
		assertFalse(LatticeContext.EMPTY.withOwnerVerifier((o, k) -> false).verifyOwner(key, key));
		assertTrue(LatticeContext.EMPTY.withOwnerVerifier((o, k) -> true).verifyOwner(Address.create(1), key));

		// A verifier authorising a delegate for a key owner lets that delegate author it
		BiPredicate<ACell, AccountKey> delegation = (o, k) -> key.equals(o) && delegate.getAccountKey().equals(k);
		SignedData<CVMLong> signed = LatticeContext.create(null, delegate, delegation).signAs(key, CVMLong.ONE);
		assertEquals(delegate.getAccountKey(), signed.getAccountKey());
		assertNull(LatticeContext.create(null, delegate).signAs(key, CVMLong.ONE));
		assertNull(LatticeContext.create(null, owner, delegation).signAs(key, CVMLong.ONE));
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

		// Delegated overrides leave the source policy unchanged.
		assertSame(originalTimestamp, original.getTimestamp());
		assertSame(originalKey, original.getSigningKey());
		assertSame(originalVerifier, original.getOwnerVerifier());
	}

	@Test
	public void testWithMethodsCanClearFixedOverrides() {
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
		assertNotSame(LatticeContext.EMPTY,empty);
		assertNull(empty.getTimestamp());
		assertNull(empty.getSigningKey());
		assertNull(empty.getOwnerVerifier());
		assertNotNull(empty.currentTimestamp());
	}

	@Test
	public void testTimestampOverrideKeepsDynamicSigningPolicy() {
		AKeyPair first=AKeyPair.generate();
		AKeyPair second=AKeyPair.generate();
		AtomicReference<AKeyPair> activeKey=new AtomicReference<>(first);
		LatticeContext dynamic=new LatticeContext() {
			@Override public AKeyPair getSigningKey() {
				return activeKey.get();
			}
		};
		LatticeContext fixedTime=dynamic.withTimestamp(CVMLong.create(123));

		assertEquals(first.getAccountKey(),fixedTime.sign(CVMLong.ONE).getAccountKey());
		activeKey.set(second);
		assertEquals(second.getAccountKey(),fixedTime.sign(CVMLong.TWO).getAccountKey());
		assertEquals(CVMLong.create(123),fixedTime.currentTimestamp());
	}

	@Test
	public void testSigningCanSelectNonPrimaryAccount() {
		AKeyPair primary=AKeyPair.generate();
		AKeyPair secondary=AKeyPair.generate();
		LatticeContext wallet=new LatticeContext() {
			@Override public AKeyPair getSigningKey() {
				return primary;
			}

			@Override public <T extends ACell> SignedData<T> sign(AccountKey accountKey,T value) {
				if (accountKey!=null && accountKey.equals(secondary.getAccountKey())) {
					return secondary.signData(value);
				}
				return super.sign(accountKey,value);
			}
		};

		assertEquals(primary.getAccountKey(),wallet.sign(CVMLong.ONE).getAccountKey());
		assertEquals(secondary.getAccountKey(),
			wallet.sign(secondary.getAccountKey(),CVMLong.TWO).getAccountKey());
		assertNull(wallet.sign(AKeyPair.generate().getAccountKey(),CVMLong.ZERO));
	}

}
