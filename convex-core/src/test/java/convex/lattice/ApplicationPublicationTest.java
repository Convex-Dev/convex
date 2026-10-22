package convex.lattice;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import convex.core.crypto.AKeyPair;
import convex.core.data.ACell;
import convex.core.data.ASet;
import convex.core.data.Index;
import convex.core.data.Keyword;
import convex.core.data.Sets;
import convex.core.data.SignedData;
import convex.core.data.prim.CVMLong;
import convex.lattice.cursor.TransactionalRootCursor;
import convex.lattice.generic.CompareLattice;
import convex.lattice.generic.KeyedLattice;
import convex.lattice.generic.LWWLattice;
import convex.lattice.generic.OwnerKeyLattice;
import convex.lattice.generic.SetLattice;
import convex.lattice.generic.SignedLattice;

public class ApplicationPublicationTest {

	private static final AKeyPair KEY=AKeyPair.createSeeded(7320);
	private static final Keyword OWNED=Keyword.intern("owned");
	private static final Keyword REGISTER=Keyword.intern("register");
	private static final Keyword IGNORED=Keyword.intern("ignored");
	private static final SignedLattice<ASet<CVMLong>> SIGNED=
		OwnerKeyLattice.create(SetLattice.<CVMLong>create()).signingFor(KEY.getAccountKey());
	// All edits deliberately have the same timestamp.
	private static final LWWLattice<CVMLong> LWW=LWWLattice.create(v -> 100);
	private static final KeyedLattice LATTICE=KeyedLattice.create(OWNED,SIGNED)
		.addLattice(REGISTER,LWW).addLattice(IGNORED,LWW);

	private static Index<Keyword,ACell> initial() {
		return LATTICE.zero().assoc(OWNED,KEY.signData(Sets.of(0))).assoc(REGISTER,CVMLong.ZERO);
	}

	/** Transactional roots route atomic updates through CAS, where the race is injected. */
	private static final class InterleavingRoot extends TransactionalRootCursor<Index<Keyword,ACell>> {
		Runnable beforeCAS;
		InterleavingRoot() { super(initial()); }
		@Override public boolean compareAndSet(Index<Keyword,ACell> expected,Index<Keyword,ACell> value) {
			Runnable action=beforeCAS;
			beforeCAS=null;
			if (action!=null) action.run();
			return super.compareAndSet(expected,value);
		}
	}

	private static final class Application extends ALatticeApplication<Index<Keyword,ACell>> {
		final SignedComponent<ASet<CVMLong>> owner;
		final AtomicInteger signatures=new AtomicInteger();
		Runnable onSign=() -> {};
		volatile AKeyPair signingKey=KEY;

		Application(LatticeHost<Index<Keyword,ACell>> host) {
			super(host);
			// The owned subtree publishes through its own fork of the host, beside this
			// application's staged root, so each fork reconciles only what it owns.
			owner=new SignedComponent<>(SIGNED,
				LatticeComponent.<SignedData<ASet<CVMLong>>>resolve(host.cursor(),LATTICE,OWNED),
				value -> {
					signatures.incrementAndGet();
					onSign.run();
					var key=signingKey;
					return key==null?null:key.signData(value);
			});
		}

		void add(long value) { owner.cursor().updateAndGet(v -> v.include(CVMLong.create(value))); }
		void editRegister(long value) { cursor.<CVMLong>path(REGISTER).set(CVMLong.create(value)); }

		@Override public Index<Keyword,ACell> merge(Index<Keyword,ACell> incoming) {
			if (incoming!=null) owner.merge(signed(incoming));
			// This application deliberately ignores all other incoming subtrees.
			return cursor.get();
		}
		@Override protected void syncChildren() { owner.sync(); }

		/** Called only when both this root and the host changed: the owned subtree is
		 *  the owner component's to publish, so it is taken from the destination. */
		@Override protected Index<Keyword,ACell> mergePublication(
				Index<Keyword,ACell> own,Index<Keyword,ACell> other) {
			CVMLong local=(CVMLong)own.get(REGISTER);
			CVMLong remote=(CVMLong)other.get(REGISTER);
			return other.assoc(REGISTER,LWW.merge(local,remote));
		}
	}

	@SuppressWarnings("unchecked")
	private static SignedData<ASet<CVMLong>> signed(Index<Keyword,ACell> root) {
		return (SignedData<ASet<CVMLong>>)root.get(OWNED);
	}

	@Test
	public void testSequentialApplicationsCombineTheSameSignedSubtree() {
		var host=LatticeHost.create(initial(),null);
		var first=new Application(host);
		var second=new Application(host);
		first.add(1);
		second.add(2);
		first.sync();
		second.sync();
		assertEquals(Sets.of(0,1,2),signed(host.cursor().get()).getValue());
		assertTrue(SIGNED.checkForeign(signed(host.cursor().get())));
		assertEquals(1,first.signatures.get());
		assertEquals(1,second.signatures.get(),"The combined payload is signed once");
		assertEquals(Sets.of(0,1,2),second.owner.cursor().get(),"A fork adopts what it published");
		second.sync();
		assertEquals(Sets.of(0,1,2),second.owner.cursor().get());
		assertEquals(1,second.signatures.get(),"An unchanged subsequent publication must not sign");
		assertNull(host.getContext().getSigningKey());
	}

	@Test
	public void testCASRaceCombinesOwnersAndReusesSignatureForUnrelatedRetry() {
		var root=new InterleavingRoot();
		var host=new LatticeHost<>(root,null);
		var first=new Application(host);
		var second=new Application(host);
		first.add(1);
		second.add(2);
		root.beforeCAS=() -> {
			first.sync();
			root.beforeCAS=() -> root.set(root.get().assoc(IGNORED,CVMLong.ONE));
		};
		second.sync();
		assertEquals(Sets.of(0,1,2),signed(root.get()).getValue());
		assertTrue(signed(root.get()).checkSignature());
		assertEquals(CVMLong.ONE,root.get().get(IGNORED));
		assertEquals(2,second.signatures.get(),"Only the changed signed payload requires re-signing");
	}

	@Test
	public void testEqualTimestampLocalEditWinsAndUnchangedSiblingDoesNotRevert() {
		var host=LatticeHost.create(initial(),null);
		var first=new Application(host);
		var second=new Application(host);
		first.editRegister(1);
		first.sync();
		second.add(2);
		second.sync();
		assertEquals(CVMLong.ONE,host.cursor().get().get(REGISTER),"Unchanged sibling must adopt published state");
		first.editRegister(3);
		first.sync();
		second.editRegister(2);
		second.sync();
		assertEquals(CVMLong.TWO,host.cursor().get().get(REGISTER),"Intentional local edit is own on equal timestamps");
	}

	@Test
	public void testEditsDuringPublicationCommitAfterSync() throws Exception {
		var host=LatticeHost.create(initial(),null);
		var first=new Application(host);
		var second=new Application(host);
		first.add(1);
		second.add(2);
		first.sync();
		CountDownLatch signing=new CountDownLatch(1);
		CountDownLatch editing=new CountDownLatch(1);
		CountDownLatch resume=new CountDownLatch(1);
		second.onSign=() -> {
			if (second.signatures.get()==1) {
				signing.countDown();
				try { assertTrue(resume.await(10,TimeUnit.SECONDS)); }
				catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
			}
		};
		try (var executor=Executors.newVirtualThreadPerTaskExecutor()) {
			var publication=executor.submit(second::sync);
			try {
				assertTrue(signing.await(10,TimeUnit.SECONDS));
				var edit=executor.submit(() -> second.owner.cursor().updateAndGet(value -> {
					editing.countDown();
					return value.include(CVMLong.create(3));
				}));
				assertTrue(editing.await(10,TimeUnit.SECONDS));
				resume.countDown();
				publication.get(10,TimeUnit.SECONDS);
				edit.get(10,TimeUnit.SECONDS);
			} finally { resume.countDown(); }
		}
		assertEquals(Sets.of(0,1,2),signed(host.cursor().get()).getValue());
		assertEquals(Sets.of(0,1,2,3),second.owner.cursor().get());
		second.sync();
		assertEquals(Sets.of(0,1,2,3),signed(host.cursor().get()).getValue());
		assertTrue(signed(host.cursor().get()).checkSignature());
	}

	@Test
	public void testHostPersistenceCannotRemergeOverAnApplicationPublication() {
		var maximum=CompareLattice.<CVMLong>create((a,b) -> a.compareTo(b));
		var host=LatticeHost.create(CVMLong.create(5),null);
		var app=new LatticeApplication<>(maximum,host);
		host.configureApplications(List.of(app));
		app.cursor().set(CVMLong.ONE);
		host.setPublicationPolicy(snapshot -> {
			app.sync();
			return snapshot;
		});
		host.sync();
		assertEquals(CVMLong.ONE,host.cursor().get(),"Host persistence must retain the newer application result");
	}

	@Test
	public void testFailedConflictSigningCanRetryWithoutDroppingEitherBranch() {
		var host=LatticeHost.create(initial(),null);
		var first=new Application(host);
		var second=new Application(host);
		first.add(1);
		second.add(2);
		second.signingKey=null;
		first.sync();
		assertThrows(IllegalStateException.class,second::sync);
		assertEquals(Sets.of(0,1),signed(host.cursor().get()).getValue());
		assertEquals(Sets.of(0,2),second.owner.cursor().get(),"A failed publication leaves the working copy intact");
		second.signingKey=KEY;
		second.sync();
		assertEquals(Sets.of(0,1,2),signed(host.cursor().get()).getValue());
	}

	@Test
	public void testApplicationMaySupplyItsOwnLattice() {
		var host=LatticeHost.create(initial(),null);
		var selected=KeyedLattice.create(REGISTER,LWW);
		var app=new LatticeApplication<>(selected,host);
		host.configureApplications(List.of(app));
		host.merge(LATTICE.zero().assoc(REGISTER,CVMLong.ONE).assoc(IGNORED,CVMLong.TWO));
		app.sync();
		assertSame(selected,app.getLattice());
		assertEquals(CVMLong.ZERO,host.cursor().get().get(REGISTER),"Incoming equal-time value loses to existing own");
		assertNull(host.cursor().get().get(IGNORED));
		app.<CVMLong>resolve(REGISTER).set(CVMLong.ONE);
		app.sync();
		assertEquals(CVMLong.ONE,host.cursor().get().get(REGISTER),"A local equal-time edit replaces its predecessor");
	}

	@Test
	public void testRootRetainsOnlyWhatApplicationsAcceptAndPublish() {
		var host=LatticeHost.create(initial(),null);
		var app=new Application(host);
		host.configureApplications(List.of(app));
		var incoming=LATTICE.zero().assoc(OWNED,KEY.signData(Sets.of(1))).assoc(IGNORED,CVMLong.ONE);
		host.merge(incoming);
		assertEquals(initial(),host.cursor().get());
		assertEquals(Sets.of(0,1),app.owner.cursor().get());
		assertEquals(0,app.signatures.get());
		app.sync();
		assertEquals(Sets.of(0,1),signed(host.cursor().get()).getValue());
		assertNull(host.cursor().get().get(IGNORED),"The host must not accept discarded incoming state itself");
	}
}
