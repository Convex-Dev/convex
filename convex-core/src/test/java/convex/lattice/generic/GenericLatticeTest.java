package convex.lattice.generic;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import convex.core.crypto.AKeyPair;
import convex.core.cvm.Keywords;
import convex.core.data.ACell;
import convex.core.data.AHashMap;
import convex.core.data.Maps;
import convex.core.data.Sets;
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
		
		assertEquals(Maps.empty(), l.merge(Maps.empty(), null));
		
		assertEquals(Maps.of(1,2,3,4), l.merge(Maps.of(1,2), Maps.of(3,4)));

		assertEquals(Maps.of(1,6,2,10), l.merge(Maps.of(1,3,2,10), Maps.of(1,6,2,5)));
	}
	
	
	/**
	 * Tests for example lattices
	 */
	@Test public void testLatticeExamples() {
		LatticeTest.doLatticeTest(MaxLattice.create(),CVMLong.ONE, CVMLong.MAX_VALUE);
		
		LatticeTest.doLatticeTest(MapLattice.create(MaxLattice.create()),Maps.of(1,2,3,4,5,6), Maps.of(1,10,5,0,6,7));

		LatticeTest.doLatticeTest(SignedLattice.create(MaxLattice.create()),KP1.signData(CVMLong.ONE), KP1.signData(CVMLong.MAX_VALUE));

		LatticeTest.doLatticeTest(SetLattice.create(),Sets.of(1,2,3,4),Sets.of(3,4,5,6));
		
		LatticeTest.doLatticeTest(KeyedLattice.create("foo",MaxLattice.create(),"bar",SetLattice.create()),Maps.of(Keywords.FOO,CVMLong.ONE), Maps.of(Keywords.BAR,Sets.of(1,2)));

		LatticeTest.doLatticeTest(CompareLattice.create((AInteger a,AInteger b)->a.compareTo(b)),CVMLong.ONE, CVMLong.MAX_VALUE);
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

}
