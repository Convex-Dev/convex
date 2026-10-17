package convex.lattice;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

import org.junit.jupiter.api.Test;

import convex.core.crypto.AKeyPair;
import convex.core.cvm.Keywords;
import convex.core.data.ACell;
import convex.core.data.ASet;
import convex.core.data.Index;
import convex.core.data.Keyword;
import convex.core.data.Sets;
import convex.core.data.SignedData;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import convex.core.store.MemoryStore;
import convex.lattice.generic.KeyedLattice;
import convex.lattice.generic.MaxLattice;
import convex.lattice.generic.OwnerKeyLattice;
import convex.lattice.generic.SetLattice;

class ApplicationIngressTest {
	private static final Keyword ITEMS=Keyword.intern("items");
	private static final Keyword UNKNOWN=Keyword.intern("unknown");
	private static final KeyedLattice LATTICE=KeyedLattice.create(ITEMS,SetLattice.create());

	/** Counts root and path deliveries and takes the default for each, unless told to ignore them. */
	private static class App extends ALatticeApplication<Index<Keyword,ACell>> {
		int merges;
		int pathMerges;
		int preparations;
		boolean ignore;
		App(KeyedLattice lattice,LatticeHost<Index<Keyword,ACell>> host) { super(lattice,host); }
		@Override public Index<Keyword,ACell> merge(Index<Keyword,ACell> incoming) {
			merges++;
			if (ignore) return cursor.get();
			return cursor.updateAndGet(own -> getLattice().merge(getContext(),own,incoming));
		}
		@Override public Index<Keyword,ACell> mergeAt(ACell value,ACell... path) {
			if (path.length>0) {
				pathMerges++;
				if (ignore) return cursor.get();
			}
			return super.mergeAt(value,path);
		}
		@Override protected void beforeSync() { preparations++; }
	}

	@Test void pathDeliveryHonoursApplicationOverrideAndDoesNotPublish() throws Exception {
		var store=new MemoryStore();
		var root=LatticeHost.create(LATTICE.zero(),store);
		var first=new App(LATTICE,root);
		var second=new App(LATTICE,root);
		second.ignore=true;
		root.configureApplications(List.of(first,second));
		var values=Sets.of(CVMLong.ONE);
		assertEquals(LATTICE.zero(),root.mergeAt(values,ITEMS));
		assertEquals(values,first.cursor().get().get(ITEMS));
		assertEquals(LATTICE.zero(),second.cursor().get());
		assertEquals(1,first.pathMerges);
		assertEquals(1,second.pathMerges);
		assertEquals(0,first.merges,"a path delivery does not pass through the root merge");
		assertEquals(0,first.preparations);
		assertNull(store.getRootData());
		first.sync();
		assertEquals(values,root.cursor().get().get(ITEMS));
		assertNull(store.getRootData());
		root.sync();
		assertEquals(root.cursor().get(),store.getRootData());
	}

	@Test void applicationsOwnPathSelectionIndependentlyOfHostSchema() {
		var root=LatticeHost.create(KeyedLattice.create().zero(),null);
		var app=new App(LATTICE,root);
		root.configureApplications(List.of(app));
		root.mergeAt(Sets.of(CVMLong.ONE),UNKNOWN);
		assertEquals(LATTICE.zero(),app.cursor().get(),"a path the application's lattice does not declare delivers nothing");
		root.mergeAt(Sets.of(CVMLong.ONE),ITEMS);
		assertEquals(Sets.of(CVMLong.ONE),app.cursor().get().get(ITEMS));
		root.mergeAt(LATTICE.zero());
		assertEquals(1,app.merges,"an empty path is the root itself");
	}

	/** An application's own component for a path receives the delivery, whatever its merge does. */
	@Test void pathDeliveryMergesWithTheApplicationsOwnComponentForThePath() {
		var root=LatticeHost.create(LATTICE.zero(),null);
		int[] routed={0};
		var app=new App(LATTICE,root) {
			@SuppressWarnings("unchecked")
			@Override public <T extends ACell> ALatticeComponent<T> component(ACell... path) {
				if (path.length==1 && ITEMS.equals(path[0])) {
					return (ALatticeComponent<T>)new ALatticeComponent<ASet<ACell>>(resolve(ITEMS)) {
						@Override public ASet<ACell> merge(ASet<ACell> other) {
							routed[0]++;
							return cursor.get(); // this component takes nothing from outside
						}
					};
				}
				return super.component(path);
			}
		};
		root.configureApplications(List.of(app));
		root.mergeAt(Sets.of(CVMLong.ONE),ITEMS);
		assertEquals(1,routed[0]);
		assertEquals(LATTICE.zero(),app.cursor().get(),"the component's merge decided, not the lattice");
		assertSame(app,app.component(),"the application is the component at the root");
		assertNull(app.component(UNKNOWN),"an undeclared path has no component");
	}

	/** Without a component of its own for a path, the application merges there with the lattice declared for that level. */
	@Test void pathDeliveryMergesAtItsLevelWithTheLatticeDeclaredThere() {
		var root=LatticeHost.create(LATTICE.zero(),null);
		var app=new App(LATTICE,root);
		root.configureApplications(List.of(app));
		assertInstanceOf(LatticeComponent.class,app.component(ITEMS),"the default component at a declared path");
		root.mergeAt(Sets.of(CVMLong.ONE),ITEMS);
		assertEquals(Sets.of(CVMLong.ONE),app.cursor().get().get(ITEMS));
		root.mergeAt(Sets.of(CVMLong.TWO),ITEMS);
		assertEquals(Sets.of(CVMLong.ONE,CVMLong.TWO),app.cursor().get().get(ITEMS),"the set lattice at the path merges");
		assertEquals(0,app.merges,"the root merge is not involved");
		root.mergeAt(Sets.of(CVMLong.create(3)),UNKNOWN);
		assertEquals(Sets.of(CVMLong.ONE,CVMLong.TWO),app.cursor().get().get(ITEMS));
		assertNull(app.cursor().get().get(UNKNOWN),"a path the lattice does not declare delivers nothing");
	}

	@Test void signedPathRequiresEnvelopeAndAuthorisedOwner() {
		var owner=AKeyPair.createSeeded(9181);
		var stranger=AKeyPair.createSeeded(9182);
		var owners=OwnerKeyLattice.create(SetLattice.<ACell>create());
		var lattice=KeyedLattice.create(ITEMS,owners);
		var root=LatticeHost.create(lattice.zero(),null);
		/** Routes the owner's slot to a signed component and leaves other paths to the default. */
		class OwnerApp extends App {
			final SignedComponent<ASet<ACell>> owned;
			OwnerApp() {
				super(lattice,root);
				owned=new SignedComponent<>(owners.signingFor(owner.getAccountKey()),resolve(ITEMS,owner.getAccountKey()));
				owned.setContext(LatticeContext.create(null,owner));
			}
			@Override public Index<Keyword,ACell> mergeAt(ACell value,ACell... path) {
				if (path.length==2 && ITEMS.equals(path[0]) && owner.getAccountKey().equals(path[1])) {
					@SuppressWarnings("unchecked")
					var envelope=(SignedData<ASet<ACell>>)value;
					owned.merge(envelope);
					return cursor.get();
				}
				return super.mergeAt(value,path);
			}
			@Override protected void beforeSync() { owned.sync(); }
		}
		var app=new OwnerApp();
		root.configureApplications(List.of(app));
		var values=Sets.of(CVMLong.ONE);
		root.mergeAt(values,ITEMS,owner.getAccountKey(),Keywords.VALUE);
		assertNull(app.owned.cursor().get(),"a path inside the envelope is outside the lattice");
		root.mergeAt(stranger.signData(values),ITEMS,owner.getAccountKey());
		assertNull(app.owned.cursor().get());
		var signed=owner.signData(values);
		root.mergeAt(signed,ITEMS,owner.getAccountKey());
		assertEquals(values,app.owned.cursor().get());
		root.mergeAt(Sets.of(CVMLong.TWO),ITEMS,owner.getAccountKey(),Keywords.VALUE);
		assertEquals(values,app.owned.cursor().get());
		assertNull(RT.getIn(app.cursor().get(),ITEMS,owner.getAccountKey()));
		assertEquals(LATTICE.zero(),root.cursor().get());
		app.sync();
		assertEquals(signed,RT.getIn(root.cursor().get(),ITEMS,owner.getAccountKey()));
	}

	private static final Keyword A=Keyword.intern("a");
	private static final Keyword B=Keyword.intern("b");
	private static final Keyword C=Keyword.intern("c");
	private static final Keyword D=Keyword.intern("d");
	private static final KeyedLattice FOUR=KeyedLattice.create(
		A,MaxLattice.INSTANCE,B,MaxLattice.INSTANCE,C,MaxLattice.INSTANCE,D,MaxLattice.INSTANCE);

	private static Index<Keyword,ACell> four(long a,long b,long c,long d) {
		return FOUR.zero().assoc(A,CVMLong.create(a)).assoc(B,CVMLong.create(b))
			.assoc(C,CVMLong.create(c)).assoc(D,CVMLong.create(d));
	}

	/**
	 * Knows the whole root, so it can navigate and publish all of it, but takes only
	 * the regions it is interested in from an incoming root: it delegates the root
	 * projected to those regions to the lattice merge, and nothing else.
	 */
	private static class SelectiveApp extends App {
		final List<Keyword> interests;
		SelectiveApp(LatticeHost<Index<Keyword,ACell>> host,Keyword... interests) {
			super(FOUR,host);
			this.interests=List.of(interests);
		}
		@Override public Index<Keyword,ACell> merge(Index<Keyword,ACell> incoming) {
			Index<Keyword,ACell> wanted=FOUR.zero();
			for (Keyword region:interests) {
				ACell value=incoming.get(region);
				if (value!=null) wanted=wanted.assoc(region,value);
			}
			return super.merge(wanted);
		}
	}

	@Test void selectiveApplicationsTakeTheirRegionsAndPublishThemToTheRoot() {
		var initial=four(1,1,1,1);
		var root=LatticeHost.create(initial,null);
		var ab=new SelectiveApp(root,A,B);
		var bc=new SelectiveApp(root,B,C);
		root.configureApplications(List.of(ab,bc));

		// The host calls each application's merge; each takes only what it wants
		root.merge(four(2,2,2,2));
		assertEquals(1,ab.merges);
		assertEquals(1,bc.merges);
		assertEquals(four(2,2,1,1),ab.cursor().get(),"AB took A and B into its working state");
		assertEquals(four(1,2,2,1),bc.cursor().get(),"BC took B and C into its working state");
		assertEquals(initial,root.cursor().get(),"nothing reaches the root until an application publishes");

		// Each publication carries its regions back; neither disturbs the other's, and D stays
		ab.sync();
		assertEquals(four(2,2,1,1),root.cursor().get());
		bc.sync();
		assertEquals(four(2,2,2,1),root.cursor().get(),"A, B and C changed; D was wanted by no application");
		ab.sync();
		assertEquals(four(2,2,2,1),root.cursor().get(),"republishing AB's stale copy of C and D changes nothing");
	}

	@Test void sequentialDeliveryPreservesEarlierWorkOnFailure() {
		var root=LatticeHost.create(LATTICE.zero(),null);
		var first=new App(LATTICE,root);
		var failure=new App(LATTICE,root) {
			@Override public Index<Keyword,ACell> mergeAt(ACell value,ACell... path) {
				throw new IllegalStateException("application failed");
			}
		};
		var last=new App(LATTICE,root);
		root.configureApplications(List.of(first,failure,last));
		assertThrows(IllegalStateException.class,() -> root.mergeAt(Sets.of(CVMLong.ONE),ITEMS));
		assertEquals(Sets.of(CVMLong.ONE),first.cursor().get().get(ITEMS));
		assertEquals(0,last.pathMerges);
		assertEquals(LATTICE.zero(),root.cursor().get());
	}
}
