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
	 * Properties for value pairs whose merge is a join: either input can accept
	 * the merged result. Use {@link #doMergeTest} for directional policies such
	 * as last-write-wins with distinct equal-timestamp values.
	 * @param v1 First valid lattice value
	 * @param v2 Second valid lattice value
	 * @param lattice Lattice consistent with values
	 */
	public static <V extends ACell> void doLatticeTest(ALattice<V> lattice, V v1, V v2, Object... path) {
		doMergeTest(lattice,v1,v2,path);
		V merged=lattice.merge(v1,v2);
		assertEquals(merged,lattice.merge(v1,merged));
		assertEquals(merged,lattice.merge(v2,merged));
	}

	/**
	 * Checks empty-state identity, idempotence and replay stability without
	 * requiring a directional policy to resolve conflicts commutatively.
	 * @param lattice Merge policy consistent with the supplied values
	 * @param v1 First valid value
	 * @param v2 Second valid value
	 * @param path Optional independently mergeable child path
	 */
	public static <V extends ACell> void doMergeTest(ALattice<V> lattice, V v1, V v2, Object... path) {
		V zero=lattice.zero();
		
		// Merges with zero
		assertEquals(v1,lattice.merge(zero,v1));
		assertSame(v1,lattice.merge(v1,zero));

		// merge(x, null): other is null, so own is returned by identity (all lattices).
		assertSame(v1,lattice.merge(v1,null));
		// merge(null, x): own is absent, so x is entirely foreign. Leaf lattices return x by
		// identity, but container lattices (#561) route every foreign entry through the child
		// merge for validation and may rebuild/filter the map — so only value-equality is
		// guaranteed here, not reference identity.
		assertEquals(v1,lattice.merge(null,v1));


		assertEquals(v2,lattice.merge(zero,v2));
		assertSame(v2,lattice.merge(v2,zero));

		// Replaying either input must leave the result unchanged.
		V merged=lattice.merge(v1, v2);
		assertEquals(merged,lattice.merge(zero,merged));
		assertSame(merged,lattice.merge(merged,zero));
		assertSame(merged,lattice.merge(merged,v1));
		assertSame(merged,lattice.merge(merged,v2));
		
		// Identity merges
		assertSame(merged,lattice.merge(merged,merged));
		assertSame(v1,lattice.merge(v1,v1));
		assertSame(v2,lattice.merge(v2,v2));
		
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
