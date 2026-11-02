package convex.p2p;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

import convex.auth.did.DIDKeyAuthorizer;
import convex.core.crypto.AKeyPair;
import convex.core.data.ACell;
import convex.core.data.AString;
import convex.core.data.AccountKey;
import convex.core.data.Blob;
import convex.core.data.Index;
import convex.core.data.Keyword;
import convex.core.data.Strings;
import convex.core.data.prim.CVMLong;
import convex.core.store.MemoryStore;
import convex.etch.EtchStore;
import convex.lattice.LatticeHost;
import convex.lattice.LocalLattice;
import convex.net.Transports;
import convex.node.NodeConfig;

/** A node's contact records are local state: edited privately, synced by the application, kept by the host. */
public class ContactRecordsTest {

	private static final AKeyPair ALICE=AKeyPair.createSeeded(9101);
	private static final AccountKey BOB=AKeyPair.createSeeded(9102).getAccountKey();
	private static final AccountKey CAROL=AKeyPair.createSeeded(9103).getAccountKey();

	private final AtomicInteger signatures=new AtomicInteger();
	private final AtomicLong time=new AtomicLong(100);

	/** Alice's node application, counting the signatures it makes. */
	private P2PApplication alice(LatticeHost<Index<Keyword,ACell>> host) {
		return host.addApplication(P2PApplication.connect(P2PLattice.ROOT,host,ALICE.getAccountKey(),
			(owner,value) -> {
				signatures.incrementAndGet();
				return ALICE.signData(value);
			},() -> CVMLong.create(time.get()),DIDKeyAuthorizer.CONVEX));
	}

	@Test void eventsEditAPrivateCopyAndSignNothing() {
		var host=LatticeHost.create();
		var root=host.cursor().get();
		var contacts=alice(host).contacts();
		contacts.contacted(BOB);
		time.set(200);
		contacts.failed(CAROL);
		contacts.failed(CAROL);

		assertSame(root,host.cursor().get());
		assertEquals(0,signatures.get());
		assertEquals(CVMLong.create(100),contacts.get(BOB).get(ContactRecords.LAST_CONTACT));
		assertEquals(CVMLong.create(2),contacts.get(CAROL).get(ContactRecords.FAILURES));
		assertEquals(CVMLong.create(200),contacts.get(CAROL).get(ContactRecords.LAST_FAILURE));

		contacts.contacted(CAROL);
		assertEquals(CVMLong.ZERO,contacts.get(CAROL).get(ContactRecords.FAILURES),"contact clears failures");
	}

	@Test void aContactKeepsTheAddressItWasLastDialledAt() {
		var contacts=alice(LatticeHost.create()).contacts();
		AString dialled=Strings.create("tcp://bob.example:18888");
		contacts.contacted(BOB,dialled);
		assertEquals(dialled,ContactRecords.address(contacts.get(BOB)));
		contacts.contacted(BOB);
		assertEquals(dialled,ContactRecords.address(contacts.get(BOB)),"a route Bob opened says nothing of where he is");
		contacts.failed(BOB);
		assertEquals(dialled,ContactRecords.address(contacts.get(BOB)));
		AString moved=Strings.create("tcp://bob.example:18889");
		contacts.contacted(BOB,moved);
		assertEquals(moved,ContactRecords.address(contacts.get(BOB)));
		assertNull(ContactRecords.address(contacts.get(CAROL)));
	}

	@Test void atMostMaxNodesHaveARecordAndFailingOnesGoFirst() {
		var contacts=alice(LatticeHost.create()).contacts();
		int max=ContactRecords.MAX_NODES;
		Random random=new Random(9104);
		AccountKey[] nodes=new AccountKey[max+2];
		for (int i=0; i<nodes.length; i++) nodes[i]=AccountKey.create(Blob.createRandom(random,AccountKey.LENGTH));
		// The first node is friendly and out of touch longest; the second has failed
		for (int i=0; i<max; i++) {
			time.incrementAndGet();
			if (i==1) contacts.failed(nodes[i]); else contacts.contacted(nodes[i]);
		}
		assertEquals(max,contacts.all().count());

		time.incrementAndGet();
		contacts.contacted(nodes[max]);
		assertEquals(max,contacts.all().count());
		assertNull(contacts.get(nodes[1]),"a node with failures goes first");
		assertNotNull(contacts.get(nodes[0]),"time alone is no evidence against a friendly node");

		time.incrementAndGet();
		contacts.contacted(nodes[max+1]);
		assertEquals(max,contacts.all().count());
		assertNull(contacts.get(nodes[0]),"then the node out of touch longest");
		assertNotNull(contacts.get(nodes[max+1]));
	}

	@Test void applicationSyncSignsOnceAndTheRecordsSurviveARestart() throws Exception {
		var store=new MemoryStore();
		var host=LatticeHost.create(store);
		var application=alice(host);
		application.contacts().contacted(BOB,Strings.create("tcp://bob.example:18888"));
		application.contacts().failed(CAROL);
		application.syncHost();
		assertEquals(1,signatures.get(),"the :local slot is signed once per sync");
		assertNotNull(host.cursor().get(LocalLattice.KEY_LOCAL,ALICE.getAccountKey()));

		var restarted=alice(LatticeHost.open(store)).contacts();
		assertEquals(application.contacts().get(BOB),restarted.get(BOB));
		assertEquals(application.contacts().get(CAROL),restarted.get(CAROL));
	}

	@Test void aNodeWithoutAKeyKeepsNoContactRecords() {
		var host=LatticeHost.create();
		var relay=host.addApplication(P2PApplication.connect(P2PLattice.ROOT,host));
		assertNull(relay.contacts());
		relay.syncHost();
	}

	@Test void connectedNodesRecordEachOtherAsContacts() throws Exception {
		AKeyPair hubKey=AKeyPair.generate();
		AKeyPair leafKey=AKeyPair.generate();
		try (EtchStore hubStore=EtchStore.createTemp("contacts-hub");
				EtchStore leafStore=EtchStore.createTemp("contacts-leaf")) {
			P2PNode hub=P2PNode.create(hubStore,NodeConfig.localNetwork(),hubKey).serveAllInbound();
			P2PNode leaf=P2PNode.create(leafStore,NodeConfig.port(-1),leafKey);
			try {
				hub.launch();
				leaf.launch();
				leaf.connect(hubKey.getAccountKey(),hub.getHostAddress()).get(5,TimeUnit.SECONDS);
				hub.whenInboundConnectionUpgraded(leafKey.getAccountKey()).get(5,TimeUnit.SECONDS);
				var hubContact=leaf.getApplication().contacts().get(hubKey.getAccountKey());
				var leafContact=hub.getApplication().contacts().get(leafKey.getAccountKey());
				assertNotNull(leafContact);
				assertEquals(Strings.create(Transports.endpoint(hub.getHostAddress()).toString()),
					ContactRecords.address(hubContact),"the leaf dialled the hub and found its key there");
				assertNull(ContactRecords.address(leafContact),"the hub was dialled, so knows no address for the leaf");
			} finally {
				leaf.close();
				hub.close();
			}
		}
	}
}
