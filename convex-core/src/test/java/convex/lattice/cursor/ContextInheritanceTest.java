package convex.lattice.cursor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.Test;

import convex.core.cvm.Keywords;
import convex.core.data.AHashMap;
import convex.core.data.ASet;
import convex.core.data.Keyword;
import convex.core.data.Maps;
import convex.core.data.Sets;
import convex.core.data.prim.AInteger;
import convex.core.data.prim.CVMLong;
import convex.lattice.LatticeContext;
import convex.lattice.generic.MapLattice;
import convex.lattice.generic.MaxLattice;
import convex.lattice.generic.SetLattice;

/** Tests live context inheritance for cursor views and snapshots for forks. */
public class ContextInheritanceTest {

	@Test
	public void testDerivedPathTracksParentAndSupportsLocalOverride() {
		MapLattice<Keyword, ASet<CVMLong>> lattice = MapLattice.create(SetLattice.create());
		RootLatticeCursor<AHashMap<Keyword, ASet<CVMLong>>> root =
			Cursors.createLattice(lattice, Maps.empty());
		ALatticeCursor<ASet<CVMLong>> child = root.path(Keywords.FOO);

		LatticeContext first = LatticeContext.create(CVMLong.create(1000), null);
		root.setContext(first);
		assertSame(first, child.getContext());

		LatticeContext override = LatticeContext.create(CVMLong.create(2000), null);
		child.setContext(override);
		LatticeContext laterParent = LatticeContext.create(CVMLong.create(3000), null);
		root.setContext(laterParent);
		assertSame(override, child.getContext());

		child.setContext(null);
		assertSame(laterParent, child.getContext());
	}

	@Test
	public void testLongLivedStampedViewObservesAdvancedParentClock() {
		RootLatticeCursor<AInteger> root = Cursors.createLattice(MaxLattice.INSTANCE, null);
		StampedCursor<AInteger> stamped = StampedCursor.create(root, MaxLattice.INSTANCE, null,
			(value, timestamp) -> timestamp);

		root.setContext(LatticeContext.create(CVMLong.create(1000), null));
		stamped.set(CVMLong.ONE);
		assertEquals(CVMLong.create(1000), root.get());

		root.setContext(LatticeContext.create(CVMLong.create(2000), null));
		stamped.set(CVMLong.ONE);
		assertEquals(CVMLong.create(2000), root.get());
	}

	@Test
	public void testForkSnapshotsEffectiveContext() {
		RootLatticeCursor<ASet<CVMLong>> root = Cursors.createLattice(SetLattice.create(), Sets.empty());
		LatticeContext atFork = LatticeContext.create(CVMLong.create(1000), null);
		root.setContext(atFork);
		ALatticeCursor<ASet<CVMLong>> fork = root.fork();

		root.setContext(LatticeContext.create(CVMLong.create(2000), null));
		assertSame(atFork, fork.getContext());

		fork.setContext(null);
		assertSame(atFork, fork.getContext());
	}

	@Test
	public void testRootClearsToEmptyContext() {
		RootLatticeCursor<ASet<CVMLong>> root = Cursors.createLattice(SetLattice.create(), Sets.empty());
		root.setContext(LatticeContext.create(CVMLong.create(1000), null));
		root.setContext(null);
		assertSame(LatticeContext.EMPTY, root.getContext());
	}
}