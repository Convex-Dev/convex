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
import convex.test.lattice.ComponentChecks;

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

	/** A view's published edit merges a user both sides changed and keeps another user's removal. */
	@SuppressWarnings("unchecked")
	@Test void viewedIdentitiesReconcileUserByUser() {
		AccountKey bob=BOB.getAccountKey();
		BiFunction<ACell,ACell,SignedData<ACell>> signer=(owner,value) -> (owner.equals(KEY)?ALICE:BOB).signData(value);
		var root=new Root<Index<Keyword,ACell>>(Index.<Keyword,ACell>none());
		var holder=new Regions(root);
		P2PIdentities held=holder.create(P2PLattice.KEY_ID,P2PIdentities::new);
		held.identity(KEY,signer).setIdentity(FIRST);
		held.identity(bob,signer).setIdentity(FIRST);
		var viewer=new Regions(root.fork());
		P2PIdentities seen=viewer.view(P2PLattice.KEY_ID,P2PIdentities::new);
		seen.identity(KEY,signer).setIdentity(THIRD);
		held.identity(KEY,signer).setIdentity(SECOND);
		// The merging application drops Bob's record
		root.update(r -> r.assoc(P2PLattice.KEY_ID,((AHashMap<ACell,ACell>)r.get(P2PLattice.KEY_ID)).dissoc(bob)));
		viewer.sync();
		assertEquals(THIRD,held.identity(KEY,null).getIdentity(),"a user changed on both sides merges by the owner rule");
		assertNull(held.identity(bob,null).getIdentity(),"the merging application's removal stays");
	}

	private static ALatticeComponent<ACell> component(P2PUser user,boolean node) {
		return node?user.node():user.identity();
	}

	/** Replaces the record through the component's own setter */
	private static void set(ALatticeComponent<ACell> component,AHashMap<Keyword,ACell> record) {
		if (component instanceof P2PNodeRecord node) node.setNodeInfo(record);
		else ((P2PIdentity)component).setIdentity(record);
	}

	/** The record the component holds */
	private static AHashMap<Keyword,ACell> record(ALatticeComponent<ACell> component) {
		return (component instanceof P2PNodeRecord node)?node.getNodeInfo():((P2PIdentity)component).getIdentity();
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
			assertFalse(view.exists());
			set(view,null);
			view.sync();
			assertNull(root.get());
			assertThrows(IllegalStateException.class,() -> set(view,FIRST));
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
			assertThrows(IllegalStateException.class,() -> view.value());
			assertThrows(IllegalStateException.class,() -> set(view,FIRST));
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
		set(component(user,node),FIRST);
		assertEquals(1,signatures.get());
		ACell created=root.get();
		Consumer<ALatticeComponent<ACell>> edits=draft -> {
			set(draft,FIRST);
			set(draft,SECOND);
			assertSame(created,root.get());
			assertEquals(1,signatures.get());
		};
		if (node) user.node().execute(edits::accept);
		else user.identity().execute(edits::accept);
		assertEquals(SECOND,component(user,node).value());
		assertEquals(2,signatures.get());
		ACell before=root.get();
		Consumer<ALatticeComponent<ACell>> abort=draft -> {
			set(draft,FIRST);
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
		set(draft,FIRST);
		set(draft,SECOND);
		assertNull(root.get());
		assertEquals(0,signatures.get());
		draft.sync();
		assertEquals(2,attempts.get(),"A conflicting root write must force a retry");
		assertEquals(SECOND,component(user,node).value());
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

	private void assertTransactionRerun(boolean node) {
		var root=new Root<ACell>();
		var user=user(root,(owner,value) -> ALICE.signData(value));
		var live=component(user,node);
		set(live,FIRST);
		var runs=new AtomicInteger();
		Consumer<ALatticeComponent<ACell>> edit=draft -> {
			// Another writer replaces the record with a newer one during the first run
			if (runs.incrementAndGet()==1) set(live,SECOND);
			set(draft,record(draft).assoc(NOTE,CVMLong.ONE));
		};
		if (node) user.node().execute(edit::accept);
		else user.identity().execute(edit::accept);
		assertEquals(2,runs.get(),"the transaction that met the change ran again");
		assertEquals(SECOND.assoc(NOTE,CVMLong.ONE),live.value(),"both changes are kept");
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
		set(live,FIRST);
		var draft=node?user.node().fork():user.identity().fork();
		set(draft,forkNewer?THIRD:SECOND);
		set(live,forkNewer?SECOND:THIRD);
		ACell before=root.get();
		assertThrows(MergeConflictException.class,draft::sync);
		assertSame(before,root.get(),"a refused sync changes nothing");
		assertEquals(forkNewer?THIRD:SECOND,draft.value(),"the fork keeps its edits");
	}

	@Test void regionKeepsTheComponentMergeRules() {
		AccountKey bob=BOB.getAccountKey();
		var region=new P2PRegion(new Root<>());
		Index<Keyword,ACell> alice=Index.of(P2PLattice.KEY_NODES,Maps.of(KEY,ALICE.signData(FIRST)));
		Index<Keyword,ACell> both=Index.of(P2PLattice.KEY_NODES,Maps.of(KEY,ALICE.signData(FIRST),bob,BOB.signData(FIRST)));
		Index<Keyword,ACell> newer=Index.of(P2PLattice.KEY_NODES,Maps.of(KEY,ALICE.signData(SECOND)));
		assertNull(ComponentChecks.checkCommutingDeliveries(region,both,newer));
		// Two records of a node at one timestamp tie: the held record stays
		Index<Keyword,ACell> tied=Index.of(P2PLattice.KEY_NODES,Maps.of(KEY,ALICE.signData(FIRST.assoc(NOTE,CVMLong.ONE))));
		assertNull(ComponentChecks.checkDeliveries(region,alice,tied));
		// A record signed by another key, and a section the region does not declare
		Index<Keyword,ACell> wrongSigner=Index.of(P2PLattice.KEY_NODES,Maps.of(KEY,BOB.signData(SECOND)));
		Index<Keyword,ACell> undeclared=Index.of(NOTE,CVMLong.ONE);
		assertNull(ComponentChecks.checkRefusedDeliveries(region,alice,wrongSigner,undeclared,CVMLong.ONE,Maps.empty()));
	}

	@Test void identitiesKeepTheComponentMergeRules() {
		AccountKey bob=BOB.getAccountKey();
		var identities=new P2PIdentities(new Root<>());
		AHashMap<ACell,SignedData<ACell>> alice=Maps.of(KEY,ALICE.signData(FIRST));
		AHashMap<ACell,SignedData<ACell>> both=Maps.of(KEY,ALICE.signData(FIRST),bob,BOB.signData(FIRST));
		AHashMap<ACell,SignedData<ACell>> newer=Maps.of(KEY,ALICE.signData(SECOND));
		assertNull(ComponentChecks.checkCommutingDeliveries(identities,both,newer));
		// Two records of a user at one timestamp tie: the held record stays
		AHashMap<ACell,SignedData<ACell>> tied=Maps.of(KEY,ALICE.signData(FIRST.assoc(NOTE,CVMLong.ONE)));
		assertNull(ComponentChecks.checkDeliveries(identities,alice,tied));
		// A record signed by another key, an entry that is not a signed record, and a value that is not an owner map
		AHashMap<ACell,ACell> wrongSigner=Maps.of(KEY,BOB.signData(SECOND));
		AHashMap<ACell,ACell> unsigned=Maps.of(KEY,CVMLong.ONE);
		assertNull(ComponentChecks.checkRefusedDeliveries(identities,alice,wrongSigner,unsigned,CVMLong.ONE));
	}

	@Test void identityKeepsTheComponentReconcileRules() {
		var identity=new P2PIdentity(new Root<>(),KEY);
		assertNull(ComponentChecks.checkConcurrentEdits(identity,FIRST,i -> i.setIdentity(SECOND),i -> i.setIdentity(THIRD)));
	}

	@Test void nodeRecordKeepsTheComponentReconcileRules() {
		var node=new P2PNodeRecord(new Root<>(),KEY);
		assertNull(ComponentChecks.checkConcurrentEdits(node,FIRST,n -> n.setNodeInfo(SECOND),n -> n.setNodeInfo(THIRD)));
	}
}
