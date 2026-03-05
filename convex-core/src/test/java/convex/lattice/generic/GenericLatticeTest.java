package convex.lattice.generic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.Set;
import java.util.function.BiPredicate;

import org.junit.jupiter.api.Test;

import convex.core.crypto.AKeyPair;
import convex.core.cvm.Keywords;
import convex.core.data.ACell;
import convex.core.data.AHashMap;
import convex.core.data.AVector;
import convex.core.data.AccountKey;
import convex.core.data.Index;
import convex.core.data.Keyword;
import convex.core.data.Maps;
import convex.core.data.Sets;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.AInteger;
import convex.core.data.prim.CVMLong;
import convex.core.data.SignedData;
import convex.lattice.ALattice;
import convex.lattice.LatticeContext;
import convex.lattice.LatticeTest;

public class GenericLatticeTest {
	
	AKeyPair KP1 = AKeyPair.createSeeded(56756785);
	AKeyPair KP2 = AKeyPair.createSeeded(756778);
	

	@Test public void testLatticeAPI() {
		
		ALattice<AHashMap<ACell,AInteger>> l=MapLattice.create(MaxLattice.create());
		
		assertSame(Maps.empty(), l.merge(Maps.empty(), null));
		
		assertEquals(Maps.of(1,2,3,4), l.merge(Maps.of(1,2), Maps.of(3,4)));

		assertEquals(Maps.of(1,6,2,10), l.merge(Maps.of(1,3,2,10), Maps.of(1,6,2,5)));
	}
	
	
	/**
	 * Tests for example lattices
	 */
	@Test public void testLatticeExamples() {
		LatticeTest.doLatticeTest(MaxLattice.create(),CVMLong.ONE, CVMLong.MAX_VALUE);

		LatticeTest.doLatticeTest(MapLattice.create(MaxLattice.create()),Maps.of(1,2,3,4,5,6), Maps.of(1,10,5,0,6,7), 1);

		LatticeTest.doLatticeTest(SignedLattice.create(MaxLattice.create()),KP1.signData(CVMLong.ONE), KP1.signData(CVMLong.MAX_VALUE), Keywords.VALUE);

		LatticeTest.doLatticeTest(SetLattice.create(),Sets.of(1,2,3,4),Sets.of(3,4,5,6));

		LatticeTest.doLatticeTest(KeyedLattice.create("foo",MaxLattice.create(),"bar",SetLattice.create()),Index.of(Keywords.FOO,CVMLong.ONE), Index.of(Keywords.BAR,Sets.of(1,2)), Keywords.FOO);

		LatticeTest.doLatticeTest(CompareLattice.create((AInteger a,AInteger b)->a.compareTo(b)),CVMLong.ONE, CVMLong.MAX_VALUE);

		LatticeTest.doLatticeTest(MinLattice.create(), CVMLong.ONE, CVMLong.TWO);

		LatticeTest.doLatticeTest(FunctionLattice.create((AInteger a, AInteger b) -> (a.compareTo(b) >= 0) ? a : b), CVMLong.ONE, CVMLong.TWO);

		LatticeTest.doLatticeTest(LWWLattice.INSTANCE,
			Maps.of(LWWLattice.KEY_TIMESTAMP, CVMLong.create(100), Keyword.intern("data"), Strings.create("a")),
			Maps.of(LWWLattice.KEY_TIMESTAMP, CVMLong.create(200), Keyword.intern("data"), Strings.create("b")));

		LatticeTest.doLatticeTest(LWWLattice.create(v -> ((CVMLong)v).longValue()), CVMLong.create(100), CVMLong.create(200));

		LatticeTest.doLatticeTest(TupleLattice.create(MaxLattice.create(), SetLattice.create()),
			(AVector<ACell>) Vectors.of(CVMLong.ONE, Sets.of(1, 2)),
			(AVector<ACell>) Vectors.of(CVMLong.TWO, Sets.of(2, 3)), 0);

		LatticeTest.doLatticeTest(VectorLattice.create(MaxLattice.create()),
			Vectors.of(1, 5, 3), Vectors.of(4, 2, 6), 0);
	}

	@Test
	public void testSignedLatticeWithContext() {
		AKeyPair kp = AKeyPair.generate();
		CVMLong ts = CVMLong.create(System.currentTimeMillis());
		LatticeContext ctx = LatticeContext.create(ts, kp);

		// Create signed lattice (without setting keypair on instance)
		SignedLattice<AInteger> sl = SignedLattice.<AInteger>create(MaxLattice.create());

		// Create signed values
		SignedData<AInteger> sd1 = kp.signData(CVMLong.create(10));
		SignedData<AInteger> sd2 = kp.signData(CVMLong.create(20));

		// Merge using context - should get max value (20) with valid signature
		SignedData<AInteger> result = sl.merge(ctx, sd1, sd2);

		assertEquals(CVMLong.create(20), result.getValue());
		assertEquals(true, result.checkSignature());
	}

	@Test
	public void testSignedLatticeContextFallback() {
		AKeyPair kp = AKeyPair.generate();

		// Create signed lattice with keypair set on instance (old style)
		SignedLattice<AInteger> sl = SignedLattice.<AInteger>create(MaxLattice.create());
		sl.setKeyPair(kp);

		// Create signed values
		SignedData<AInteger> sd1 = kp.signData(CVMLong.create(10));
		SignedData<AInteger> sd2 = kp.signData(CVMLong.create(20));

		// Merge with empty context - should fall back to instance keypair
		SignedData<AInteger> result = sl.merge(LatticeContext.EMPTY, sd1, sd2);

		assertEquals(CVMLong.create(20), result.getValue());
		assertEquals(true, result.checkSignature());
	}

	@Test
	public void testOwnerLatticeWithContext() {
		// Create two key pairs for two different owners
		AKeyPair kp1 = AKeyPair.generate();
		AKeyPair kp2 = AKeyPair.generate();
		CVMLong ts = CVMLong.create(System.currentTimeMillis());

		// Create OwnerLattice for signed max values
		OwnerLattice<AInteger> ownerLattice = OwnerLattice.create(MaxLattice.create());

		// Create first owner's signed values
		ACell owner1 = kp1.getAccountKey();
		SignedData<AInteger> owner1Val1 = kp1.signData(CVMLong.create(10));
		AHashMap<ACell, SignedData<AInteger>> map1 = Maps.of(owner1, owner1Val1);

		// Create second owner's signed values
		ACell owner2 = kp2.getAccountKey();
		SignedData<AInteger> owner2Val1 = kp2.signData(CVMLong.create(20));
		AHashMap<ACell, SignedData<AInteger>> map2 = Maps.of(owner2, owner2Val1);

		// Merge two different owners - should combine both entries
		LatticeContext ctx1 = LatticeContext.create(ts, kp1);
		AHashMap<ACell, SignedData<AInteger>> merged = ownerLattice.merge(ctx1, map1, map2);

		assertEquals(2, merged.size(), "Merged map should have both owners");
		assertEquals(CVMLong.create(10), merged.get(owner1).getValue(), "Owner1's value should be preserved");
		assertEquals(CVMLong.create(20), merged.get(owner2).getValue(), "Owner2's value should be preserved");

		// Update owner1's value and merge again
		SignedData<AInteger> owner1Val2 = kp1.signData(CVMLong.create(30));
		AHashMap<ACell, SignedData<AInteger>> map3 = Maps.of(owner1, owner1Val2);

		LatticeContext ctx2 = LatticeContext.create(ts, kp1);
		AHashMap<ACell, SignedData<AInteger>> merged2 = ownerLattice.merge(ctx2, merged, map3);

		assertEquals(2, merged2.size(), "Merged map should still have both owners");
		assertEquals(CVMLong.create(30), merged2.get(owner1).getValue(), "Owner1's value should be updated to max (30)");
		assertEquals(CVMLong.create(20), merged2.get(owner2).getValue(), "Owner2's value should remain unchanged");

		// Verify signatures are valid
		assertEquals(true, merged2.get(owner1).checkSignature(), "Owner1's signature should be valid");
		assertEquals(true, merged2.get(owner2).checkSignature(), "Owner2's signature should be valid");
	}

	@Test
	public void testOwnerLatticeContextFallback() {
		// Test that OwnerLattice works without context (backwards compatibility)
		AKeyPair kp1 = AKeyPair.generate();
		AKeyPair kp2 = AKeyPair.generate();

		OwnerLattice<AInteger> ownerLattice = OwnerLattice.create(MaxLattice.create());

		// Create owner values
		ACell owner1 = kp1.getAccountKey();
		SignedData<AInteger> owner1Val = kp1.signData(CVMLong.create(10));
		AHashMap<ACell, SignedData<AInteger>> map1 = Maps.of(owner1, owner1Val);

		ACell owner2 = kp2.getAccountKey();
		SignedData<AInteger> owner2Val = kp2.signData(CVMLong.create(20));
		AHashMap<ACell, SignedData<AInteger>> map2 = Maps.of(owner2, owner2Val);

		// Merge without context (basic merge)
		AHashMap<ACell, SignedData<AInteger>> merged = ownerLattice.merge(map1, map2);

		assertEquals(2, merged.size(), "Merged map should have both owners");
		assertEquals(CVMLong.create(10), merged.get(owner1).getValue());
		assertEquals(CVMLong.create(20), merged.get(owner2).getValue());
	}

	// ===== Owner verification tests =====

	@Test
	public void testOwnerVerificationBlobKeyMatch() {
		// Blob/AccountKey owner matching the signer → accepted
		AKeyPair kpOwner = AKeyPair.generate();
		AKeyPair kpOther = AKeyPair.generate();

		OwnerLattice<AInteger> ownerLattice = OwnerLattice.create(MaxLattice.create());

		// Owner map with correct signer
		ACell ownerKey = kpOwner.getAccountKey();
		SignedData<AInteger> signed = kpOwner.signData(CVMLong.create(42));
		AHashMap<ACell, SignedData<AInteger>> incoming = Maps.of(ownerKey, signed);

		// Merge with context (any context triggers verification for blob keys)
		LatticeContext ctx = LatticeContext.create(null, kpOther);
		AHashMap<ACell, SignedData<AInteger>> result = ownerLattice.merge(ctx, Maps.empty(), incoming);

		assertNotNull(result.get(ownerKey), "Correctly signed entry should be accepted");
		assertEquals(CVMLong.create(42), result.get(ownerKey).getValue());
	}

	@Test
	public void testOwnerVerificationBlobKeyMismatch() {
		// Blob/AccountKey owner NOT matching the signer → rejected
		AKeyPair kpOwner = AKeyPair.generate();
		AKeyPair kpFake = AKeyPair.generate();
		AKeyPair kpMerger = AKeyPair.generate();

		OwnerLattice<AInteger> ownerLattice = OwnerLattice.create(MaxLattice.create());

		// Sign with kpFake but claim to be kpOwner
		ACell ownerKey = kpOwner.getAccountKey();
		SignedData<AInteger> forged = kpFake.signData(CVMLong.create(42));
		AHashMap<ACell, SignedData<AInteger>> incoming = Maps.of(ownerKey, forged);

		// Merge with context — should reject because signer doesn't match owner
		LatticeContext ctx = LatticeContext.create(null, kpMerger);
		AHashMap<ACell, SignedData<AInteger>> result = ownerLattice.merge(ctx, Maps.empty(), incoming);

		assertNull(result.get(ownerKey), "Mismatched signer should be rejected");
	}

	@Test
	public void testOwnerVerificationWithCustomVerifier() {
		// String owner with custom verifier
		AKeyPair kpAuthorised = AKeyPair.generate();
		AKeyPair kpUnauthorised = AKeyPair.generate();
		AKeyPair kpMerger = AKeyPair.generate();

		AccountKey authorisedKey = kpAuthorised.getAccountKey();

		// Verifier that accepts a specific key for the string owner "org:acme"
		BiPredicate<ACell, AccountKey> verifier = (owner, signerKey) -> {
			if (owner instanceof convex.core.data.AString s && "org:acme".equals(s.toString())) {
				return authorisedKey.equals(signerKey);
			}
			return false;
		};

		OwnerLattice<AInteger> ownerLattice = OwnerLattice.create(MaxLattice.create());
		LatticeContext ctx = LatticeContext.create(null, kpMerger, verifier);

		ACell ownerKey = Strings.create("org:acme");

		// Authorised signer → accepted
		SignedData<AInteger> goodSigned = kpAuthorised.signData(CVMLong.create(10));
		AHashMap<ACell, SignedData<AInteger>> goodIncoming = Maps.of(ownerKey, goodSigned);
		AHashMap<ACell, SignedData<AInteger>> result1 = ownerLattice.merge(ctx, Maps.empty(), goodIncoming);

		assertNotNull(result1.get(ownerKey), "Authorised signer should be accepted");
		assertEquals(CVMLong.create(10), result1.get(ownerKey).getValue());

		// Unauthorised signer → rejected
		SignedData<AInteger> badSigned = kpUnauthorised.signData(CVMLong.create(20));
		AHashMap<ACell, SignedData<AInteger>> badIncoming = Maps.of(ownerKey, badSigned);
		AHashMap<ACell, SignedData<AInteger>> result2 = ownerLattice.merge(ctx, Maps.empty(), badIncoming);

		assertNull(result2.get(ownerKey), "Unauthorised signer should be rejected");
	}

	@Test
	public void testOwnerVerificationMultipleKeys() {
		// Organisation with multiple authorised keys
		AKeyPair kpA = AKeyPair.generate();
		AKeyPair kpB = AKeyPair.generate();
		AKeyPair kpRogue = AKeyPair.generate();
		AKeyPair kpMerger = AKeyPair.generate();

		Set<AccountKey> authorisedKeys = Set.of(kpA.getAccountKey(), kpB.getAccountKey());

		BiPredicate<ACell, AccountKey> verifier = (owner, signerKey) -> {
			if (owner instanceof convex.core.data.AString s && "org:multi".equals(s.toString())) {
				return authorisedKeys.contains(signerKey);
			}
			return false;
		};

		OwnerLattice<AInteger> ownerLattice = OwnerLattice.create(MaxLattice.create());
		LatticeContext ctx = LatticeContext.create(null, kpMerger, verifier);
		ACell ownerKey = Strings.create("org:multi");

		// Key A → accepted
		SignedData<AInteger> signedA = kpA.signData(CVMLong.create(10));
		AHashMap<ACell, SignedData<AInteger>> incomingA = Maps.of(ownerKey, signedA);
		AHashMap<ACell, SignedData<AInteger>> resultA = ownerLattice.merge(ctx, Maps.empty(), incomingA);
		assertNotNull(resultA.get(ownerKey), "Key A should be accepted");

		// Key B → accepted
		SignedData<AInteger> signedB = kpB.signData(CVMLong.create(20));
		AHashMap<ACell, SignedData<AInteger>> incomingB = Maps.of(ownerKey, signedB);
		AHashMap<ACell, SignedData<AInteger>> resultB = ownerLattice.merge(ctx, Maps.empty(), incomingB);
		assertNotNull(resultB.get(ownerKey), "Key B should be accepted");

		// Rogue key → rejected
		SignedData<AInteger> signedRogue = kpRogue.signData(CVMLong.create(30));
		AHashMap<ACell, SignedData<AInteger>> incomingRogue = Maps.of(ownerKey, signedRogue);
		AHashMap<ACell, SignedData<AInteger>> resultRogue = ownerLattice.merge(ctx, Maps.empty(), incomingRogue);
		assertNull(resultRogue.get(ownerKey), "Rogue key should be rejected");
	}

	@Test
	public void testMinLattice() {
		MinLattice lat = MinLattice.create();
		assertSame(CVMLong.ONE, lat.merge(CVMLong.ONE, CVMLong.TWO));
		assertSame(CVMLong.ONE, lat.merge(CVMLong.TWO, CVMLong.ONE));
		assertSame(CVMLong.ONE, lat.merge(CVMLong.ONE, null));
		assertSame(CVMLong.ONE, lat.merge(null, CVMLong.ONE));
		assertNull(lat.merge(null, null));

		LatticeTest.doLatticeTest(lat, CVMLong.ONE, CVMLong.TWO);
		LatticeTest.doLatticeTest(lat, CVMLong.MAX_VALUE, CVMLong.ZERO);
	}

	@Test
	public void testFunctionLattice() {
		// Create a "max" lattice via FunctionLattice
		FunctionLattice<AInteger> lat = FunctionLattice.create(
			(a, b) -> (a.compareTo(b) >= 0) ? a : b
		);
		assertSame(CVMLong.TWO, lat.merge(CVMLong.ONE, CVMLong.TWO));
		assertSame(CVMLong.TWO, lat.merge(CVMLong.TWO, CVMLong.ONE));
		assertSame(CVMLong.ONE, lat.merge(CVMLong.ONE, null));
		assertSame(CVMLong.ONE, lat.merge(null, CVMLong.ONE));

		LatticeTest.doLatticeTest(lat, CVMLong.ONE, CVMLong.TWO);
	}

	@Test
	public void testTupleLattice() {
		// Tuple: [MaxLattice, SetLattice]
		TupleLattice lat = TupleLattice.create(MaxLattice.create(), SetLattice.create());

		AVector<ACell> v1 = (AVector<ACell>) Vectors.of(CVMLong.ONE, Sets.of(1, 2));
		AVector<ACell> v2 = (AVector<ACell>) Vectors.of(CVMLong.TWO, Sets.of(2, 3));

		AVector<ACell> merged = lat.merge(v1, v2);
		assertEquals(CVMLong.TWO, merged.get(0));
		assertEquals(Sets.of(1, 2, 3), merged.get(1));

		// Identity preservation
		assertSame(v1, lat.merge(v1, null));
		assertSame(v1, lat.merge(null, v1));
		assertSame(v1, lat.merge(v1, v1));

		// zero
		AVector<ACell> zero = lat.zero();
		assertEquals(2, zero.count());

		// path — child lattice at index
		assertNotNull(lat.path(CVMLong.ZERO));
		assertNotNull(lat.path(CVMLong.ONE));
		assertNull(lat.path(CVMLong.TWO));
		assertNull(lat.path(Keywords.FOO));

		LatticeTest.doLatticeTest(lat, v1, v2, 0);
	}

	@SuppressWarnings({ "unchecked", "rawtypes" })
	@Test
	public void testVectorLattice() {
		VectorLattice lat = VectorLattice.create(MaxLattice.create());

		AVector v1 = Vectors.of(1, 5, 3);
		AVector v2 = Vectors.of(4, 2, 6);

		AVector merged = lat.merge(v1, v2);
		assertEquals(Vectors.of(4, 5, 6), merged);

		// Identity preservation
		assertSame(v1, lat.merge(v1, null));
		assertSame(v1, lat.merge(null, v1));
		assertSame(v1, lat.merge(v1, v1));

		// Merge where one side wins completely
		AVector low = Vectors.of(0, 0, 0);
		assertSame(v1, lat.merge(v1, low));
		assertSame(v1, lat.merge(low, v1));

		// Different-length merge
		AVector longer = Vectors.of(0, 0, 0, 10, 20);
		AVector m2 = lat.merge(v1, longer);
		assertEquals(5, m2.count());
		assertEquals(CVMLong.ONE, m2.get(0));
		assertEquals(CVMLong.create(5), m2.get(1));
		assertEquals(CVMLong.create(3), m2.get(2));
		assertEquals(CVMLong.create(10), m2.get(3));

		// zero and path
		assertEquals(Vectors.empty(), lat.zero());
		assertNotNull(lat.path(CVMLong.ZERO));
		assertNull(lat.path(Keywords.FOO));

		LatticeTest.doLatticeTest(lat, v1, v2, 0);
	}

	@Test
	public void testOwnerVerificationNoVerifierLenient() {
		// No verifier set → lenient mode, string owners accepted
		AKeyPair kp = AKeyPair.generate();

		OwnerLattice<AInteger> ownerLattice = OwnerLattice.create(MaxLattice.create());

		// Use string owner with no verifier (lenient)
		ACell ownerKey = Strings.create("anyone");
		SignedData<AInteger> signed = kp.signData(CVMLong.create(99));
		AHashMap<ACell, SignedData<AInteger>> incoming = Maps.of(ownerKey, signed);

		// Context without verifier
		LatticeContext ctx = LatticeContext.create(null, kp);
		AHashMap<ACell, SignedData<AInteger>> result = ownerLattice.merge(ctx, Maps.empty(), incoming);

		assertNotNull(result.get(ownerKey), "Lenient mode should accept any string owner");
	}

}
