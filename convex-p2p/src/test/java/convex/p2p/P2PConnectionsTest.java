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

/** A node keeps the records it wants: pinned ones, answered ones, and the best of the rest up to its bound. */
public class P2PConnectionsTest {

	private static final int BOUND=4;
	private static final AKeyPair ALICE=AKeyPair.createSeeded(9201);

	private final LatticeHost<Index<Keyword,ACell>> host=LatticeHost.create();
	private final P2PApplication alice=host.addApplication(P2PApplication.connect(P2PLattice.ROOT,host,
		ALICE.getAccountKey(),(owner,value) -> ALICE.signData(value),() -> CVMLong.create(100),DIDKeyAuthorizer.CONVEX));

	{
		alice.connections().setMaxRecords(BOUND);
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

	@Test void pinnedRecordsAreKeptAndNotCounted() {
		alice.directory().publishOwnRecord(null);
		AKeyPair named=key(100),routed=key(101),pop=key(102);
		alice.connections().name(named.getAccountKey());
		alice.connections().setRoutes(() -> Set.of(routed.getAccountKey()));
		alice.directory().setPointsOfPresence(Vectors.of(pop.getAccountKey()));

		alice.merge(registry(records(0,10,1)));
		alice.merge(registry(record(named,1).merge(record(routed,1)).merge(record(pop,1))));
		var held=held();
		for (AKeyPair key:new AKeyPair[] {ALICE,named,routed,pop}) assertNotNull(held.get(key.getAccountKey()));
		assertEquals(BOUND+4,held.count());
	}

	@Test void anAnsweredNodeIsNeverDisplacedAndAFailedOneGoesFirst() {
		AKeyPair answered=key(100),failed=key(101),fresh=key(102);
		alice.connections().setMaxRecords(2);
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
		alice.connections().setRoutes(() -> Set.of(routed.getAccountKey()));
		alice.connections().setMaxRecords(0);
		alice.merge(registry(record(routed,1)));
		alice.directory().refresh();
		assertTrue(alice.directory().records().containsKey(routed.getAccountKey()));

		alice.connections().setRoutes(() -> Set.of());
		alice.merge(registry(record(key(101),1)));
		alice.directory().refresh();
		assertEquals(0,held().count());
		assertFalse(alice.directory().records().containsKey(routed.getAccountKey()));
	}

	@SuppressWarnings("unchecked")
	private AHashMap<ACell,SignedData<ACell>> held() {
		ACell nodes=host.cursor().get(P2PLattice.KEY_P2P,P2PLattice.KEY_NODES);
		return (AHashMap<ACell,SignedData<ACell>>)nodes;
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
