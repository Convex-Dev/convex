package convex.lattice.app;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

import org.junit.jupiter.api.Test;

import convex.core.data.ACell;
import convex.core.data.ASet;
import convex.core.data.Index;
import convex.core.data.Keyword;
import convex.core.data.Sets;
import convex.core.data.prim.CVMLong;
import convex.lattice.ALatticeApplication;
import convex.lattice.LatticeHost;
import convex.lattice.generic.KeyedLattice;
import convex.lattice.generic.SetLattice;

/** Staged applications on one host publish only the regions their lattice declares. */
class SharedRegionsTest {
	private static final Keyword A=Keyword.intern("a");
	private static final Keyword B=Keyword.intern("b");
	private static final KeyedLattice A_LATTICE=KeyedLattice.create(A,SetLattice.create());
	private static final KeyedLattice B_LATTICE=KeyedLattice.create(B,SetLattice.create());

	/** One region per application; each is staged at the whole root. */
	static final class RegionApp extends ALatticeApplication<Index<Keyword,ACell>> {
		private final Keyword region;
		RegionApp(KeyedLattice lattice,Keyword region,LatticeHost<Index<Keyword,ACell>> host) {
			super(lattice,host);
			this.region=region;
		}
		@Override public Index<Keyword,ACell> merge(Index<Keyword,ACell> other) {
			return cursor.updateAndGet(own -> getLattice().merge(getContext(),own,other));
		}
		void add(long item) {
			this.<ASet<ACell>>resolve(region).updateAndGet(items ->
				(items==null?Sets.<ACell>empty():items).include(CVMLong.create(item)));
		}
	}

	private static ACell region(LatticeHost<Index<Keyword,ACell>> host,Keyword key) {
		return host.cursor().get().get(key);
	}

	@Test void aStagedApplicationNeverPublishesRegionsItDoesNotDeclare() {
		var host=LatticeHost.create();
		var a=new RegionApp(A_LATTICE,A,host);
		var b=new RegionApp(B_LATTICE,B,host);
		host.configureApplications(List.of(a,b));

		a.add(1);
		a.sync();
		assertEquals(Sets.of(1),region(host,A));

		b.add(2);
		b.sync(); // b's copy predates :a entirely
		assertEquals(Sets.of(1),region(host,A),"publishing :b must not remove :a");
		assertEquals(Sets.of(2),region(host,B));

		a.add(3);
		a.sync(); // a's copy holds no :b
		assertEquals(Sets.of(2),region(host,B),"publishing :a must not remove :b");
		assertEquals(Sets.of(1,3),region(host,A));

		b.add(4);
		a.add(5);
		a.sync();
		b.sync(); // both moved: declared regions merge, the rest adopts the host
		assertEquals(Sets.of(1,3,5),region(host,A));
		assertEquals(Sets.of(2,4),region(host,B));
		assertEquals(host.cursor().get(),b.cursor().get(),"after sync the copy carries the host's other regions");
	}

	@Test void incomingRegionsSurviveAnotherApplicationsPublication() {
		var host=LatticeHost.create();
		var a=new RegionApp(A_LATTICE,A,host);
		var b=new RegionApp(B_LATTICE,B,host);
		host.configureApplications(List.of(a,b));

		host.merge(Index.<Keyword,ACell>none().assoc(A,Sets.of(1)).assoc(B,Sets.of(2)));
		assertTrue(host.cursor().get().isEmpty(),"staged applications merge into their own copies");
		assertEquals(Sets.of(1),a.cursor().get().get(A));
		assertNull(a.cursor().get().get(B),"an application ignores incoming regions it does not declare");
		assertEquals(Sets.of(2),b.cursor().get().get(B));

		a.sync();
		assertEquals(Sets.of(1),region(host,A));
		assertNull(region(host,B));

		b.add(3);
		b.sync();
		assertEquals(Sets.of(1),region(host,A),"an incoming region published by one application survives another's sync");
		assertEquals(Sets.of(2,3),region(host,B));
	}
}
