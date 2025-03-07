package convex.core.lattice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;

import convex.core.data.ACell;
import convex.core.data.AHashMap;
import convex.core.data.Maps;
import convex.core.data.prim.AInteger;
import convex.core.data.prim.CVMLong;

public class LatticeTest {

	@Test public void testLatticeAPI() {
		
		ALattice<AHashMap<ACell,AInteger>> l=MapNode.create(MaxNode.create());
		
		assertEquals(Maps.empty(), l.merge(Maps.empty(), null));
		
		assertEquals(Maps.of(1,2,3,4), l.merge(Maps.of(1,2), Maps.of(3,4)));

		assertEquals(Maps.of(1,6,2,10), l.merge(Maps.of(1,3,2,10), Maps.of(1,6,2,5)));
	}
	
	
	
	@Test public void testLattices() {
		doLatticeTest(MaxNode.create(),CVMLong.ONE, CVMLong.MAX_VALUE);
		
		doLatticeTest(MapNode.create(MaxNode.create()),Maps.of(1,2,3,4,5,6), Maps.of(1,10,5,0,6,7));
	}



	/**
	 * GEneraic property tests for any lattice
	 * @param maxNode
	 * @param one
	 */
	private <V extends ACell> void doLatticeTest(ALattice<V> lattice, V value, V value2) {
		V zero=lattice.zero();
		assertNotNull(zero);
		
		// Merges with zero
		assertEquals(value,lattice.merge(zero,value));
		assertEquals(value,lattice.merge(value,zero));
		
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
