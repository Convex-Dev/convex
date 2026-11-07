package convex.lattice;

import static org.junit.jupiter.api.Assertions.*;


import org.junit.jupiter.api.Test;

import convex.core.crypto.AKeyPair;
import convex.core.cvm.Keywords;
import convex.core.data.Keyword;
import convex.core.data.Sets;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import convex.lattice.generic.KeyedLattice;
import convex.lattice.generic.OwnerLattice;
import convex.lattice.generic.SetLattice;

/** A component whose merge, path delivery and publication are a supplied lattice, registered at a host root. */
class LatticeComponentTest {
	private static final Keyword ITEMS=Keyword.intern("items");
	private static final KeyedLattice LATTICE=KeyedLattice.create(ITEMS,SetLattice.create());

	@Test void stagedRootAcceptsPathsButWaitsForPublication() {
		var host=LatticeHost.create();
		var app=new LatticeComponent<>(LATTICE,host.cursor().fork());
		host.addApplication(app);
		host.mergeAt(Sets.of(1),ITEMS);
		assertEquals(Sets.of(1),app.cursor().get().get(ITEMS));
		assertTrue(host.cursor().get().isEmpty());
		app.sync();
		assertEquals(app.cursor().get(),host.cursor().get());
	}

	@Test void ownerPathsRetainAuthorisation() {
		var owner=AKeyPair.createSeeded(9183);
		var stranger=AKeyPair.createSeeded(9184);
		var lattice=KeyedLattice.create(ITEMS,OwnerLattice.create(SetLattice.create()));
		var root=LatticeHost.create();
		root.addApplication(new LatticeComponent<>(lattice,root.cursor()));
		var values=Sets.of(CVMLong.ONE);
		root.mergeAt(stranger.signData(values),ITEMS,owner.getAccountKey());
		assertNull(RT.getIn(root.cursor().get(),ITEMS,owner.getAccountKey()));
		var signed=owner.signData(values);
		root.mergeAt(signed,ITEMS,owner.getAccountKey());
		assertEquals(signed,RT.getIn(root.cursor().get(),ITEMS,owner.getAccountKey()));
		root.mergeAt(Sets.of(CVMLong.TWO),ITEMS,owner.getAccountKey(),Keywords.VALUE);
		assertEquals(signed,RT.getIn(root.cursor().get(),ITEMS,owner.getAccountKey()),
			"a path inside the envelope is outside the lattice and delivers nothing");
	}

}
