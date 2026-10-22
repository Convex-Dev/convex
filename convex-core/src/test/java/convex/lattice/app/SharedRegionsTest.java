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
import convex.lattice.LatticeComponent;
import convex.lattice.LatticeHost;
import convex.lattice.Regions;
import convex.lattice.SetComponent;
import convex.lattice.cursor.AForkableCursor;
import convex.lattice.generic.SetLattice;

/**
 * Two applications share a host while owning disjoint regions. Each {@link Regions}
 * container is a live view of the host root; each {@link Items} child owns a staged
 * set at its registered path. The staging boundary is the child, not the root.
 *
 * <p>These assembly tests deliberately use minimal regions {@code :a} and
 * {@code :b}. They establish that routing and publication affect only registered
 * children, even when both applications were opened against an empty host.
 * Incoming roots are delivered to both applications, but receipt does not publish
 * their children. Sharing a host here does not mean sharing a region's working
 * copy; that separate case is covered by the favourites application tests.</p>
 */
class SharedRegionsTest {
	private static final Keyword A=Keyword.intern("a");
	private static final Keyword B=Keyword.intern("b");

	/** A staged grow-only set; union is the rule for both incoming merge and publication conflicts. */
	static final class Items extends LatticeComponent<ASet<ACell>> {
		private final SetComponent<ACell> items;
		Items(AForkableCursor<ASet<ACell>> destination) {
			super(SetLattice.create(),forkOf(destination));
			items=new SetComponent<>(cursor());
		}
		void add(long item) { items.add(CVMLong.create(item)); }
	}

	private static ACell region(LatticeHost<Index<Keyword,ACell>> host,Keyword key) {
		return host.cursor().get().get(key);
	}

	/** Alternating publications cannot overwrite a sibling with an old root snapshot. */
	@Test void aStagedApplicationNeverPublishesRegionsItDoesNotDeclare() {
		var host=LatticeHost.create();
		var a=new Regions(host);
		var aItems=a.create(A,Items::new);
		var b=new Regions(host);
		var bItems=b.create(B,Items::new);
		host.configureApplications(List.of(a,b));

		aItems.add(1);
		a.sync();
		assertEquals(Sets.of(1),region(host,A));

		bItems.add(2);
		b.sync(); // b was constructed before :a existed
		assertEquals(Sets.of(1),region(host,A),"publishing :b must not remove :a");
		assertEquals(Sets.of(2),region(host,B));

		aItems.add(3);
		a.sync(); // only a's registered child publishes
		assertEquals(Sets.of(2),region(host,B),"publishing :a must not remove :b");
		assertEquals(Sets.of(1,3),region(host,A));

		bItems.add(4);
		aItems.add(5);
		a.sync();
		b.sync(); // both moved: each publication touches only its region
		assertEquals(Sets.of(1,3,5),region(host,A));
		assertEquals(Sets.of(2,4),region(host,B));
		assertSame(host.cursor(),b.cursor(),"the container is a view of the host, not a private copy");
	}

	/** One incoming root updates both working regions, whose publication remains independent. */
	@Test void incomingRegionsSurviveAnotherApplicationsPublication() {
		var host=LatticeHost.create();
		var a=new Regions(host);
		var aItems=a.create(A,Items::new);
		var b=new Regions(host);
		var bItems=b.create(B,Items::new);
		host.configureApplications(List.of(a,b));

		host.merge(Index.<Keyword,ACell>none().assoc(A,Sets.of(1)).assoc(B,Sets.of(2)));
		assertTrue(host.cursor().get().isEmpty(),"staged regions merge into their own copies");
		assertEquals(Sets.of(1),aItems.cursor().get());
		assertEquals(Sets.of(2),bItems.cursor().get());

		// Publishing :a neither discards nor implicitly publishes :b's received working state.
		a.sync();
		assertEquals(Sets.of(1),region(host,A));
		assertNull(region(host,B));

		bItems.add(3);
		b.sync();
		assertEquals(Sets.of(1),region(host,A),"an incoming region published by one application survives another's sync");
		assertEquals(Sets.of(2,3),region(host,B));
	}
}
