package convex.p2p;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

import org.junit.jupiter.api.Test;

import convex.core.crypto.AKeyPair;
import convex.core.data.ACell;
import convex.core.data.AHashMap;
import convex.core.data.AccountKey;
import convex.core.data.Index;
import convex.core.data.Keyword;
import convex.core.data.Maps;
import convex.core.data.SignedData;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;
import convex.lattice.ALatticeComponent;
import convex.lattice.MergeConflictException;
import convex.lattice.Regions;
import convex.lattice.cursor.AForkableCursor;
import convex.lattice.cursor.Root;

/** Domain storage properties need neither a node nor a disk store. */
class P2PComponentStorageTest {
	private static final AKeyPair ALICE=AKeyPair.createSeeded(7500);
	private static final AKeyPair BOB=AKeyPair.createSeeded(7501);
	private static final AccountKey KEY=ALICE.getAccountKey();
	private static final AHashMap<Keyword,ACell> FIRST=
		P2PLattice.createIdentity(Strings.intern("alice"),null,null,1);
	private static final AHashMap<Keyword,ACell> SECOND=
		P2PLattice.createIdentity(Strings.intern("updated"),null,null,2);
	private static final AHashMap<Keyword,ACell> THIRD=
		P2PLattice.createIdentity(Strings.intern("third"),null,null,3);
	private static final Keyword NOTE=Keyword.intern("note");

	/** A user's components over an application that views the P2P regions of a root. */
	private static P2PUser user(Root<ACell> root,BiFunction<ACell,ACell,SignedData<ACell>> signer) {
		var application=new Regions(root.<Index<Keyword,ACell>>path());
		P2PIdentities identities=application.view(P2PLattice.KEY_ID,P2PIdentities::new);
		P2PRegion region=application.view(P2PLattice.KEY_P2P,P2PRegion::new);
		return P2PUser.create(identities.identity(KEY,signer),region.node(KEY,signer));
	}

	/** A view's published edit keeps the record the merging application dropped meanwhile. */
	@Test void viewedRegionKeepsTheRecordTheMergingApplicationDropped() {
		AccountKey bob=BOB.getAccountKey();
		AHashMap<Keyword,ACell> info=P2PLattice.createNodeInfo(Vectors.empty(),Strings.create("node"),Strings.create("test"),null,1);
		Index<Keyword,ACell> nodes=Index.of(P2PLattice.KEY_NODES,Maps.of(bob,BOB.signData(info)));
		var root=new Root<Index<Keyword,ACell>>(Index.of(P2PLattice.KEY_P2P,nodes));
		var holder=new Regions(root);
		NodeRegistry registry=holder.create(P2PLattice.KEY_P2P,
			(AForkableCursor<Index<Keyword,ACell>> cursor) -> new NodeRegistry(cursor,null,node -> false,() -> node -> 1));
		var viewer=new Regions(root.fork());
		P2PRegion seen=viewer.view(P2PLattice.KEY_P2P,P2PRegion::new);
		seen.node(KEY,(owner,value) -> ALICE.signData(value)).setNodeInfo(info);
		registry.drop(bob);
		viewer.sync();
		AHashMap<?,?> published=(AHashMap<?,?>)((Index<?,?>)root.get().get(P2PLattice.KEY_P2P)).get(P2PLattice.KEY_NODES);
		assertNotNull(published.get(KEY),"the view's record is published");
		assertNull(published.get(bob),"the record the merging application dropped stays dropped");
	}

	private static ALatticeComponent<ACell> component(P2PUser user,boolean node) {
		return node?user.node():user.identity();
	}

	@Test void missingOrWrongSignerLeavesAllAncestorsAbsent() {
		for (boolean node:new boolean[] {false,true}) assertFailedSigning(node);
	}

	private void assertFailedSigning(boolean node) {
		for (int mode=0;mode<3;mode++) {
			var root=new Root<ACell>();
			BiFunction<ACell,ACell,SignedData<ACell>> signer=switch (mode) {
				case 0 -> null;
				case 1 -> (owner,value) -> null;
				default -> (owner,value) -> BOB.signData(value);
			};
			var view=component(user(root,signer),node);
			assertFalse(view.cursor().exists());
			view.cursor().set(null);
			view.sync();
			assertNull(root.get());
			assertThrows(IllegalStateException.class,() -> view.cursor().set(FIRST));
			assertNull(root.get(),"Failed signing must not create even an enclosing region");
		}
	}

	@Test void incompatibleAncestorsThrowWithoutReplacement() {
		for (boolean node:new boolean[] {false,true}) assertIncompatibleAncestors(node);
	}

	private void assertIncompatibleAncestors(boolean node) {
		var root=new Root<ACell>();
		var view=component(user(root,(owner,value) -> ALICE.signData(value)),node);
		List<ACell> invalid=node
			?List.of(CVMLong.ONE,Maps.empty(),Index.of(P2PLattice.KEY_P2P,Maps.empty()),
				Index.of(P2PLattice.KEY_P2P,Index.of(P2PLattice.KEY_NODES,Index.none())))
			:List.of(CVMLong.ONE,Maps.empty(),Index.of(P2PLattice.KEY_ID,CVMLong.ONE),
				Index.of(P2PLattice.KEY_ID,Index.none()));
		for (ACell value:invalid) {
			root.set(value);
			assertThrows(IllegalStateException.class,() -> view.cursor().get());
			assertThrows(IllegalStateException.class,() -> view.cursor().set(FIRST));
			assertSame(value,root.get());
		}
	}

	@Test void transactionsSignOnceAndAbortWithoutPublishing() {
		for (boolean node:new boolean[] {false,true}) assertTransactionBoundary(node);
	}

	private void assertTransactionBoundary(boolean node) {
		var root=new Root<ACell>();
		var signatures=new AtomicInteger();
		var user=user(root,(owner,value) -> {
			assertEquals(KEY,owner);
			signatures.incrementAndGet();
			return ALICE.signData(value);
		});
		// A transaction needs its record to exist, so an ordinary write creates it first
		component(user,node).cursor().set(FIRST);
		assertEquals(1,signatures.get());
		ACell created=root.get();
		Consumer<ALatticeComponent<ACell>> edits=draft -> {
			draft.cursor().set(FIRST);
			draft.cursor().set(SECOND);
			assertSame(created,root.get());
			assertEquals(1,signatures.get());
		};
		if (node) user.node().execute(edits::accept);
		else user.identity().execute(edits::accept);
		assertEquals(SECOND,component(user,node).cursor().get());
		assertEquals(2,signatures.get());
		ACell before=root.get();
		Consumer<ALatticeComponent<ACell>> abort=draft -> {
			draft.cursor().set(FIRST);
			throw new IllegalArgumentException("Aborted edit");
		};
		assertThrows(IllegalArgumentException.class,() -> {
			if (node) user.node().execute(abort::accept);
			else user.identity().execute(abort::accept);
		});
		assertSame(before,root.get());
		assertEquals(2,signatures.get());
	}

	/** A root conflict during signing must preserve both unrelated regions and owners. */
	@Test void forkPublicationRetainsConcurrentOpaqueSiblings() {
		for (boolean node:new boolean[] {false,true}) assertForkPublication(node);
	}

	private void assertForkPublication(boolean node) {
		var attempts=new AtomicInteger();
		var root=new Root<ACell>() {
			@Override public ACell updateAndGet(UnaryOperator<ACell> update) {
				return super.updateAndGet(current -> {
					attempts.incrementAndGet();
					return update.apply(current);
				});
			}
		};
		var signatures=new AtomicInteger();
		var opaque=Strings.intern("future data");
		var owners=Maps.of(BOB.getAccountKey(),opaque);
		Index<Keyword,ACell> concurrent=Index.of(P2PLattice.KEY_KAD,opaque);
		concurrent=node?concurrent.assoc(P2PLattice.KEY_P2P,Index.of(P2PLattice.KEY_NODES,owners))
			:concurrent.assoc(P2PLattice.KEY_ID,owners);
		var winner=concurrent;
		var user=user(root,(owner,value) -> {
			if (signatures.incrementAndGet()==1) root.set(winner);
			return ALICE.signData(value);
		});
		var draft=node?user.node().fork():user.identity().fork();
		draft.cursor().set(FIRST);
		draft.cursor().set(SECOND);
		assertNull(root.get());
		assertEquals(0,signatures.get());
		draft.sync();
		assertEquals(2,attempts.get(),"A conflicting root write must force a retry");
		assertEquals(SECOND,component(user,node).cursor().get());
		var actual=(Index<?,?>)root.get();
		assertSame(opaque,actual.get(P2PLattice.KEY_KAD));
		ACell region=node?((Index<?,?>)actual.get(P2PLattice.KEY_P2P)).get(P2PLattice.KEY_NODES)
			:actual.get(P2PLattice.KEY_ID);
		var stored=(AHashMap<?,?>)region;
		assertSame(opaque,stored.get(BOB.getAccountKey()));
		var signed=(SignedData<?>)stored.get(KEY);
		assertEquals(KEY,signed.getAccountKey());
		assertTrue(signed.checkSignature());
	}

	/** A transaction that meets a concurrent change to the record runs again from it, so both changes are kept. */
	@Test void transactionMeetingAConcurrentChangeRunsAgain() {
		for (boolean node:new boolean[] {false,true}) assertTransactionRerun(node);
	}

	@SuppressWarnings("unchecked")
	private void assertTransactionRerun(boolean node) {
		var root=new Root<ACell>();
		var user=user(root,(owner,value) -> ALICE.signData(value));
		var live=component(user,node);
		live.cursor().set(FIRST);
		var runs=new AtomicInteger();
		Consumer<ALatticeComponent<ACell>> edit=draft -> {
			// Another writer replaces the record with a newer one during the first run
			if (runs.incrementAndGet()==1) live.cursor().set(SECOND);
			draft.cursor().updateAndGet(record -> ((AHashMap<Keyword,ACell>)record).assoc(NOTE,CVMLong.ONE));
		};
		if (node) user.node().execute(edit::accept);
		else user.identity().execute(edit::accept);
		assertEquals(2,runs.get(),"the transaction that met the change ran again");
		assertEquals(SECOND.assoc(NOTE,CVMLong.ONE),live.cursor().get(),"both changes are kept");
	}

	/** A fork whose record changed too cannot sync, whichever change is newer, and the sync changes nothing. */
	@Test void forkSyncRefusesAChangeOnBothSides() {
		for (boolean node:new boolean[] {false,true}) {
			for (boolean forkNewer:new boolean[] {false,true}) assertForkConflict(node,forkNewer);
		}
	}

	private void assertForkConflict(boolean node,boolean forkNewer) {
		var root=new Root<ACell>();
		var user=user(root,(owner,value) -> ALICE.signData(value));
		var live=component(user,node);
		live.cursor().set(FIRST);
		var draft=node?user.node().fork():user.identity().fork();
		draft.cursor().set(forkNewer?THIRD:SECOND);
		live.cursor().set(forkNewer?SECOND:THIRD);
		ACell before=root.get();
		assertThrows(MergeConflictException.class,draft::sync);
		assertSame(before,root.get(),"a refused sync changes nothing");
		assertEquals(forkNewer?THIRD:SECOND,draft.cursor().get(),"the fork keeps its edits");
	}
}
