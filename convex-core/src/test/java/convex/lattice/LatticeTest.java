package convex.lattice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.Test;

import convex.core.cvm.Symbols;
import convex.core.data.ACell;
import convex.core.data.ASet;
import convex.core.data.Sets;
import convex.core.data.prim.AInteger;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import convex.lattice.cursor.Root;
import convex.lattice.generic.MaxLattice;
import convex.lattice.generic.SetLattice;

public class LatticeTest {

	/**
	 * Generic property tests for any lattice
	 * @param v1 First valid lattice value
	 * @param v2 Second valid lattice value
	 * @param lattice Lattice consistent with values
	 */
	public static <V extends ACell> void doLatticeTest(ALattice<V> lattice, V v1, V v2, Object... path) {
		V zero=lattice.zero();
		
		// Merges with zero
		assertEquals(v1,lattice.merge(zero,v1));
		assertEquals(v1,lattice.merge(v1,zero));

		// Null merge
		assertEquals(v1,lattice.merge(v1,null));
		assertEquals(v1,lattice.merge(null,v1));

		
		assertEquals(v2,lattice.merge(zero,v2));
		assertEquals(v2,lattice.merge(v2,zero));
		
		// Merge of both values should be idempotent (lattice join)
		V merged=lattice.merge(v1, v2);		
		assertEquals(merged,lattice.merge(zero,merged));
		assertEquals(merged,lattice.merge(merged,zero));
		assertEquals(merged,lattice.merge(v1,merged));
		assertEquals(merged,lattice.merge(v2,merged));
		assertEquals(merged,lattice.merge(merged,v1));
		assertEquals(merged,lattice.merge(merged,v2));
		assertEquals(merged,lattice.merge(merged,merged));
		
		assertSame(lattice,lattice.path());
		
		if (path.length>0) {
			ALattice<ACell> child=lattice.path(path);
			assertNotNull(child);
			
			ACell c1=RT.getIn(v1,path);
			ACell c2=RT.getIn(v2,path);
			
			assertEquals(child.merge(c1,c2),RT.getIn(merged, path));
		}
	}
	
	@Test public void testLattice() {
		ACell genesis=Lattice.ROOT.zero();
		var root=Root.create(genesis);
		assertSame(genesis,root.get());
		
		// ACursor dl=Lattice.path(root,genesis);
	}
	
	@Test public void testMaxLattice() {
		MaxLattice lattice=MaxLattice.INSTANCE;
		assertSame(CVMLong.TWO,lattice.merge(RT.cvm(1), RT.cvm(2)));
		
		doLatticeTest(lattice,CVMLong.ONE,CVMLong.ZERO);
		doLatticeTest(lattice,CVMLong.ONE,CVMLong.TWO);
		assertNull(lattice.path(Symbols.FOO));
	}
	
	@Test public void testSetLattice() {
		SetLattice<AInteger> lattice=SetLattice.create();
		ASet<AInteger> FULL=Sets.of(1,2,3);
		assertEquals(FULL,lattice.merge(Sets.of(1,2),Sets.of(2,3)));
		
		doLatticeTest(lattice,Sets.of(1,2),Sets.of(2,3));
		assertNull(lattice.path(Symbols.FOO));
	}
}
