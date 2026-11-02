package convex.p2p;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Set;

import org.junit.jupiter.api.Test;

import convex.auth.did.DIDKeyAuthorizer;
import convex.core.crypto.AKeyPair;
import convex.core.cvm.Keywords;
import convex.core.data.ACell;
import convex.core.data.AHashMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Index;
import convex.core.data.Keyword;
import convex.core.data.Maps;
import convex.core.data.SignedData;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;
import convex.lattice.LatticeHost;
import convex.node.NodeConfig;

/** A node keeps the records it wants: pinned ones, answered ones, and the best of the rest up to its bound. */
public class NodeRegistryTest {

	private static final int BOUND=4;
	private static final AKeyPair ALICE=AKeyPair.createSeeded(9201);

	private final LatticeHost<Index<Keyword,ACell>> host=LatticeHost.create();
	private final P2PApplication alice=host.addApplication(P2PApplication.connect(P2PLattice.ROOT,host,
		ALICE.getAccountKey(),(owner,value) -> ALICE.signData(value),() -> CVMLong.create(100),DIDKeyAuthorizer.CONVEX));

	{
		alice.registry().setMaxRecords(BOUND);
	}

	@Test void aFullRegistryKeepsItsBound() {
		alice.merge(registry(records(0,10,1)));
		assertEquals(BOUND,held().count());
		alice.merge(registry(records(10,20,1)));
		assertEquals(BOUND,held().count());
	}

	@Test void aRecordTurnedAwayIsTurnedAwayAgain() {
		var offered=records(0,10,1);
		alice.merge(registry(offered));
		var root=host.cursor().get();
		alice.merge(registry(offered));
		for (var entry:offered.entrySet()) alice.merge(registry(Maps.of(entry.getKey(),entry.getValue())));
		assertSame(root,host.cursor().get(),"merging what it has seen again changes nothing");
	}

	@Test void theOwnRecordAndPinnedRecordsAreKeptAndNotCounted() {
		alice.directory().publishOwnRecord(null);
		AKeyPair named=key(100),routed=key(101);
		alice.registry().setPinned(() -> Set.of(named.getAccountKey(),routed.getAccountKey()));

		alice.merge(registry(records(0,10,1)));
		alice.merge(registry(record(named,1).merge(record(routed,1))));
		var held=held();
		for (AKeyPair key:new AKeyPair[] {ALICE,named,routed}) assertNotNull(held.get(key.getAccountKey()));
		assertEquals(BOUND+3,held.count());
	}

	@Test void aNodeWithoutAKeyBoundsItsRegistryToo() {
		var relayHost=LatticeHost.<Index<Keyword,ACell>>create();
		var relay=relayHost.addApplication(P2PApplication.connect(P2PLattice.ROOT,relayHost));
		relay.registry().setMaxRecords(2);
		relay.merge(registry(records(0,3,1)));
		ACell nodes=relayHost.cursor().get(P2PLattice.KEY_P2P,P2PLattice.KEY_NODES);
		assertEquals(2,((AHashMap<?,?>)nodes).count());
	}

	@Test void answeredNodesAreBoundedToo() {
		alice.registry().setMaxRecords(2);
		for (int i=0; i<3; i++) alice.contacts().contacted(key(i).getAccountKey());
		alice.merge(registry(records(0,3,1)));
		assertEquals(2,held().count());
	}

	@Test void anAnsweredNodeOutranksOthersAndAFailedOneGoesFirst() {
		AKeyPair answered=key(100),failed=key(101),fresh=key(102);
		alice.registry().setMaxRecords(2);
		alice.merge(registry(record(answered,1).merge(record(failed,1))));
		alice.contacts().contacted(answered.getAccountKey());
		alice.contacts().failed(failed.getAccountKey());

		alice.merge(registry(record(fresh,1)));
		var held=held();
		assertNotNull(held.get(answered.getAccountKey()));
		assertNotNull(held.get(fresh.getAccountKey()),"a node never reached ranks above one that failed");
		assertNull(held.get(failed.getAccountKey()));

		alice.merge(registry(records(0,10,1)));
		assertNotNull(held().get(answered.getAccountKey()));
	}

	@Test void aNewerRecordReplacesTheHeldOneInAFullRegistry() {
		alice.merge(registry(records(0,10,1)));
		var held=held();
		ACell node=held.entrySet().iterator().next().getKey();
		AKeyPair key=keyFor(node);
		alice.merge(registry(record(key,2)));
		var info=(AHashMap<?,?>)held().get(node).getValue();
		assertEquals(CVMLong.create(2),info.get(Keywords.TIMESTAMP));
		assertEquals(BOUND,held().count());
	}

	@Test void recordsItCannotUseAreRefused() {
		AKeyPair bob=key(100),carol=key(101);
		AVector<AString> oversized=Vectors.of(Strings.create("x".repeat((int)P2PLattice.MAX_NODE_RECORD_SIZE)));
		alice.merge(registry(P2PLattice.createSignedEntry(bob,P2PLattice.createNodeInfo(oversized,
			Strings.create("test"),Strings.create("test"),null,1))));
		alice.merge(registry(P2PLattice.createSignedEntry(carol,info(1).assoc(Keywords.REGIONS,Strings.create("p2p")))));
		assertEquals(0,held().count());

		alice.merge(registry(record(bob,1)));
		alice.merge(registry(P2PLattice.createSignedEntry(bob,P2PLattice.createNodeInfo(oversized,
			Strings.create("test"),Strings.create("test"),null,2))));
		assertEquals(record(bob,1).get(bob.getAccountKey()),held().get(bob.getAccountKey()),"a newer record it cannot use leaves the held one");
	}

	@Test void theDirectoryDropsRecordsNoLongerHeld() {
		AKeyPair routed=key(100);
		alice.registry().setPinned(() -> Set.of(routed.getAccountKey()));
		alice.registry().setMaxRecords(0);
		alice.merge(registry(record(routed,1)));
		alice.directory().refresh();
		assertTrue(alice.directory().records().containsKey(routed.getAccountKey()));

		alice.registry().setPinned(() -> Set.of());
		alice.merge(registry(record(key(101),1)));
		alice.directory().refresh();
		assertEquals(0,held().count());
		assertFalse(alice.directory().records().containsKey(routed.getAccountKey()));
	}

	@Test void aDeliveryAtTheRegistryPathIsBoundedToo() {
		alice.mergeAt(records(0,10,1),P2PLattice.KEY_P2P,P2PLattice.KEY_NODES);
		assertEquals(BOUND,held().count());
	}

	@Test void raisingTheBoundAdmitsRecordsTurnedAway() {
		var offered=records(0,10,1);
		alice.merge(registry(offered));
		alice.registry().setMaxRecords(10);
		alice.merge(registry(offered));
		assertEquals(10,held().count());
	}

	@Test void aBlockedNodeIsDroppedAndRefused() {
		AKeyPair bob=key(100);
		alice.merge(registry(record(bob,1)));
		alice.directory().refresh();
		alice.block(bob.getAccountKey());
		assertNull(held().get(bob.getAccountKey()));
		assertFalse(alice.directory().records().containsKey(bob.getAccountKey()));

		alice.merge(registry(record(bob,2)));
		assertNull(held().get(bob.getAccountKey()),"a blocked node's newer record is refused");
	}

	@Test void aRefreshReportsOnlyWhatChanged() {
		alice.merge(registry(records(0,3,1)));
		assertEquals(3,alice.directory().refresh().size());
		assertTrue(alice.directory().refresh().isEmpty(),"nothing changed");

		AKeyPair newer=key(0);
		alice.merge(registry(record(newer,2)));
		var changed=alice.directory().refresh();
		assertEquals(1,changed.size());
		assertEquals(newer.getAccountKey(),changed.get(0).peerKey());
	}

	@Test void theBoundIsConfiguredOnTheNode() {
		assertEquals(NodeRegistry.DEFAULT_MAX_RECORDS,P2PNode.maxNodeRecords(NodeConfig.create()));
		assertEquals(5,P2PNode.maxNodeRecords(config(5)));
		assertEquals(0,P2PNode.maxNodeRecords(config(0)));
		assertThrows(IllegalArgumentException.class,() -> P2PNode.maxNodeRecords(config(-1)));
	}

	private static NodeConfig config(long maxNodeRecords) {
		return NodeConfig.create(Maps.of(P2PNode.MAX_NODE_RECORDS,CVMLong.create(maxNodeRecords)));
	}

	@SuppressWarnings("unchecked")
	private AHashMap<ACell,SignedData<ACell>> held() {
		ACell nodes=host.cursor().get(P2PLattice.KEY_P2P,P2PLattice.KEY_NODES);
		return (nodes==null)?Maps.empty():(AHashMap<ACell,SignedData<ACell>>)nodes;
	}

	private static Index<Keyword,ACell> registry(AHashMap<ACell,SignedData<ACell>> nodes) {
		return Index.of(P2PLattice.KEY_P2P,Index.of(P2PLattice.KEY_NODES,nodes));
	}

	/** Records of the seeded keys in {@code [from,to)}. */
	private static AHashMap<ACell,SignedData<ACell>> records(int from,int to,long timestamp) {
		AHashMap<ACell,SignedData<ACell>> records=Maps.empty();
		for (int i=from; i<to; i++) records=records.merge(record(key(i),timestamp));
		return records;
	}

	private static AHashMap<ACell,SignedData<ACell>> record(AKeyPair key,long timestamp) {
		return P2PLattice.createSignedEntry(key,info(timestamp));
	}

	private static AHashMap<Keyword,ACell> info(long timestamp) {
		return P2PLattice.createNodeInfo(Vectors.empty(),Strings.create("test"),Strings.create("test"),null,timestamp);
	}

	private static AKeyPair key(int i) {
		return AKeyPair.createSeeded(9300+i);
	}

	private static AKeyPair keyFor(ACell node) {
		for (int i=0; i<10; i++) if (key(i).getAccountKey().equals(node)) return key(i);
		throw new IllegalArgumentException("Not a seeded key: "+node);
	}
}
