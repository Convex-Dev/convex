package convex.lattice;

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
import convex.lattice.generic.CompareLattice;
import convex.lattice.generic.KeyedLattice;
import convex.lattice.generic.MapLattice;
import convex.lattice.generic.MaxLattice;
import convex.lattice.generic.SetLattice;
import convex.lattice.generic.SignedLattice;

public class LatticeTest {




	/**
	 * Generic property tests for any lattice
	 * @param value1 First valid lattice value
	 * @param value2 Second valid lattice value
	 * @param lattice Lattice consistent with values
	 */
	public static <V extends ACell> void doLatticeTest(ALattice<V> lattice, V value, V value2) {
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
