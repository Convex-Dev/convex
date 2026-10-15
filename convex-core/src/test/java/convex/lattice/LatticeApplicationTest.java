package convex.lattice;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

import org.junit.jupiter.api.Test;

import convex.core.crypto.AKeyPair;
import convex.core.cvm.Keywords;
import convex.core.data.Keyword;
import convex.core.data.Sets;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import convex.core.store.MemoryStore;
import convex.lattice.generic.KeyedLattice;
import convex.lattice.generic.OwnerLattice;
import convex.lattice.generic.SetLattice;

class LatticeApplicationTest {
	private static final Keyword ITEMS=Keyword.intern("items");
	private static final KeyedLattice LATTICE=KeyedLattice.create(ITEMS,SetLattice.create());

	@Test void privateApplicationAcceptsPathsButWaitsForPublication() {
		var host=LatticeHost.create(new MemoryStore());
		var app=new LatticeApplication<>(LATTICE,host);
		host.configureApplications(List.of(app));
		host.mergeAt(Sets.of(1),ITEMS);
		assertEquals(Sets.of(1),app.cursor().get().get(ITEMS));
		assertTrue(host.cursor().get().isEmpty());
		app.sync();
		assertEquals(app.cursor().get(),host.cursor().get());
	}

	@Test void legacyOwnerPathsRetainAuthorisation() {
		var owner=AKeyPair.createSeeded(9183);
		var stranger=AKeyPair.createSeeded(9184);
		var lattice=KeyedLattice.create(ITEMS,OwnerLattice.create(SetLattice.create()));
		var root=LatticeHost.create(new MemoryStore());
		root.configureApplications(List.of(convex.lattice.LatticeApplication.live(lattice,root)));
		var values=Sets.of(CVMLong.ONE);
		root.mergeAt(stranger.signData(values),ITEMS,owner.getAccountKey());
		assertNull(RT.getIn(root.cursor().get(),ITEMS,owner.getAccountKey()));
		var signed=owner.signData(values);
		root.mergeAt(signed,ITEMS,owner.getAccountKey());
		assertEquals(signed,RT.getIn(root.cursor().get(),ITEMS,owner.getAccountKey()));
		assertThrows(IllegalArgumentException.class,
			() -> root.mergeAt(values,ITEMS,owner.getAccountKey(),Keywords.VALUE));
	}

}
