package convex.core.lattice;

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

public class LatticeTest {
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
		doLatticeTest(MaxLattice.create(),CVMLong.ONE, CVMLong.MAX_VALUE);
		
		doLatticeTest(MapLattice.create(MaxLattice.create()),Maps.of(1,2,3,4,5,6), Maps.of(1,10,5,0,6,7));

		doLatticeTest(SignedLattice.create(MaxLattice.create()),KP1.signData(CVMLong.ONE), KP1.signData(CVMLong.MAX_VALUE));

		doLatticeTest(SetLattice.create(),Sets.of(1,2,3,4),Sets.of(3,4,5,6));
		
		doLatticeTest(KeyedLattice.create("foo",MaxLattice.create(),"bar",SetLattice.create()),Maps.of(Keywords.FOO,CVMLong.ONE), Maps.of(Keywords.BAR,Sets.of(1,2)));

		doLatticeTest(CompareLattice.create((AInteger a,AInteger b)->a.compareTo(b)),CVMLong.ONE, CVMLong.MAX_VALUE);
	}



	/**
	 * Generic property tests for any lattice
	 * @param maxNode
	 * @param one
	 */
	private <V extends ACell> void doLatticeTest(ALattice<V> lattice, V value, V value2) {
		V zero=lattice.zero();
		
		// Merges with zero
		assertEquals(value,lattice.merge(zero,value));
		assertEquals(value,lattice.merge(value,zero));

		// Null merge
		assertEquals(value,lattice.merge(value,null));
		assertEquals(value,lattice.merge(null,value));

		
		assertEquals(value2,lattice.merge(zero,value2));
		assertEquals(value2,lattice.merge(value2,zero));
		
		// Merge of both values should be idempotent (lattice join)
		V merged=lattice.merge(value, value2);		
		assertEquals(merged,lattice.merge(zero,merged));
		assertEquals(merged,lattice.merge(merged,zero));
		assertEquals(merged,lattice.merge(value,merged));
		assertEquals(merged,lattice.merge(value2,merged));
		assertEquals(merged,lattice.merge(merged,value));
		assertEquals(merged,lattice.merge(merged,value2));
		assertEquals(merged,lattice.merge(merged,merged));
	}
}
