package convex.lattice;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.ArrayList;

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

	private static class App extends ALatticeApplication<Index<Keyword,ACell>> {
		int merges;
		int preparations;
		boolean ignore;
		App(KeyedLattice lattice,LatticeHost<Index<Keyword,ACell>> host) { super(lattice,host); }
		@Override public Index<Keyword,ACell> merge(Index<Keyword,ACell> incoming) {
			merges++;
			return ignore?cursor.get():super.merge(incoming);
		}
		@Override protected void beforeSync() { preparations++; }
	}

	/** This application chooses one input path and feeds it through its own root policy. */
	private static class ItemsApp extends App {
		ItemsApp(LatticeHost<Index<Keyword,ACell>> host) { super(LATTICE,host); }
		@Override public Index<Keyword,ACell> mergeAt(ACell value,ACell... path) {
			if (path.length==1 && ITEMS.equals(path[0])) return merge(LATTICE.zero().assoc(ITEMS,value));
			return super.mergeAt(value,path);
		}
	}

	@Test void pathDeliveryHonoursRootOverrideAndDoesNotPublish() throws Exception {
		var store=new MemoryStore();
		var root=LatticeHost.create(LATTICE.zero(),store);
		var first=new ItemsApp(root);
		var second=new ItemsApp(root);
		second.ignore=true;
		root.configureApplications(List.of(first,second));
		var values=Sets.of(CVMLong.ONE);
		assertEquals(LATTICE.zero(),root.mergeAt(values,ITEMS));
		assertEquals(values,first.cursor().get().get(ITEMS));
		assertEquals(LATTICE.zero(),second.cursor().get());
		assertEquals(1,first.merges);
		assertEquals(1,second.merges);
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
		var app=new ItemsApp(root);
		root.configureApplications(List.of(app));
		root.mergeAt(Sets.of(CVMLong.ONE),UNKNOWN);
		assertEquals(0,app.merges);
		root.mergeAt(Sets.of(CVMLong.ONE),ITEMS);
		assertEquals(1,app.merges);
		assertEquals(Sets.of(CVMLong.ONE),app.cursor().get().get(ITEMS));
		root.mergeAt(LATTICE.zero());
		assertEquals(2,app.merges,"empty paths dispatch through the root merge override");
	}

	@Test void declaredPathDoesNotImplyApplicationInterest() {
		var root=LatticeHost.create(LATTICE.zero(),null);
		var app=new App(LATTICE,root);
		root.configureApplications(List.of(app));
		root.mergeAt(Sets.of(CVMLong.ONE),ITEMS);
		assertEquals(0,app.merges);
		assertEquals(LATTICE.zero(),app.cursor().get());
		root.mergeAt(LATTICE.zero().assoc(ITEMS,Sets.of(CVMLong.ONE)));
		assertEquals(1,app.merges);
		assertEquals(Sets.of(CVMLong.ONE),app.cursor().get().get(ITEMS));
	}

	/** An application states interest in every declared path by delegating to the base helper. */
	@Test void declaredPathHelperAcceptsDeclaredPathsOnly() {
		var root=LatticeHost.create(LATTICE.zero(),null);
		var app=new App(LATTICE,root) {
			@Override public Index<Keyword,ACell> mergeAt(ACell value,ACell... path) {
				return mergeDeclaredPath(value,path);
			}
		};
		root.configureApplications(List.of(app));
		root.mergeAt(Sets.of(CVMLong.ONE),ITEMS);
		assertEquals(Sets.of(CVMLong.ONE),app.cursor().get().get(ITEMS));
		assertEquals(0,app.merges,"a path merge does not pass through the root merge");
		assertThrows(IllegalArgumentException.class,() -> root.mergeAt(Sets.of(CVMLong.TWO),UNKNOWN));
		assertEquals(Sets.of(CVMLong.ONE),app.cursor().get().get(ITEMS));
		root.mergeAt(LATTICE.zero().assoc(ITEMS,Sets.of(CVMLong.TWO)));
		assertEquals(1,app.merges,"an empty path still dispatches through the root merge");
		assertEquals(Sets.of(CVMLong.ONE,CVMLong.TWO),app.cursor().get().get(ITEMS));
	}

	@Test void customHandlerReceivesInputUnchangedWithoutSchemaInterpretation() {
		var root=LatticeHost.create(KeyedLattice.create().zero(),null);
		ACell[] supplied={UNKNOWN,CVMLong.ONE};
		ACell value=CVMLong.TWO;
		List<ACell[]> deliveries=new ArrayList<>();
		var app=new App(KeyedLattice.create(),root) {
			@Override public Index<Keyword,ACell> mergeAt(ACell incoming,ACell... path) {
				assertSame(value,incoming);
				deliveries.add(path);
				merges++;
				return cursor.get();
			}
		};
		root.configureApplications(List.of(app));
		root.mergeAt(value,supplied);
		ACell[] empty={};
		root.mergeAt(value,empty);
		assertSame(supplied,deliveries.get(0));
		assertSame(empty,deliveries.get(1));
		assertEquals(2,app.merges);
	}

	@Test void signedPathRequiresEnvelopeAndAuthorisedOwner() {
		var owner=AKeyPair.createSeeded(9181);
		var stranger=AKeyPair.createSeeded(9182);
		var owners=OwnerKeyLattice.create(SetLattice.<ACell>create());
		var lattice=KeyedLattice.create(ITEMS,owners);
		var root=LatticeHost.create(lattice.zero(),null);
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
		assertNull(app.owned.cursor().get());
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
	 * the regions it is interested in from an incoming root, delegating to the default
	 * merge at each of those paths and nowhere else.
	 */
	private static class SelectiveApp extends App {
		final List<Keyword> interests;
		SelectiveApp(LatticeHost<Index<Keyword,ACell>> host,Keyword... interests) {
			super(FOUR,host);
			this.interests=List.of(interests);
		}
		@Override public Index<Keyword,ACell> merge(Index<Keyword,ACell> incoming) {
			merges++;
			for (Keyword region:interests) {
				ACell value=incoming.get(region);
				if (value!=null) mergeDeclaredPath(value,region);
			}
			return cursor.get();
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
		var first=new ItemsApp(root);
		var failure=new ItemsApp(root) {
			@Override public Index<Keyword,ACell> merge(Index<Keyword,ACell> incoming) {
				throw new IllegalStateException("application failed");
			}
		};
		var last=new ItemsApp(root);
		root.configureApplications(List.of(first,failure,last));
		assertThrows(IllegalStateException.class,() -> root.mergeAt(Sets.of(CVMLong.ONE),ITEMS));
		assertEquals(Sets.of(CVMLong.ONE),first.cursor().get().get(ITEMS));
		assertEquals(0,last.merges);
		assertEquals(LATTICE.zero(),root.cursor().get());
	}
}
