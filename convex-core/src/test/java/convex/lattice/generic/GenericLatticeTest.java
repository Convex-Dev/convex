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
import convex.lattice.ALattice;
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

}
