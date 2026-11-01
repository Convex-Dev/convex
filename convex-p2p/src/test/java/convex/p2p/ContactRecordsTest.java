package convex.p2p;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

import convex.auth.did.DIDKeyAuthorizer;
import convex.core.crypto.AKeyPair;
import convex.core.data.ACell;
import convex.core.data.AccountKey;
import convex.core.data.Index;
import convex.core.data.Keyword;
import convex.core.data.prim.CVMLong;
import convex.core.store.MemoryStore;
import convex.etch.EtchStore;
import convex.lattice.LatticeHost;
import convex.lattice.LocalLattice;
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

	@Test void applicationSyncSignsOnceAndTheRecordsSurviveARestart() throws Exception {
		var store=new MemoryStore();
		var host=LatticeHost.create(store);
		var application=alice(host);
		application.contacts().contacted(BOB);
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
				assertNotNull(leaf.getApplication().contacts().get(hubKey.getAccountKey()));
				assertNotNull(hub.getApplication().contacts().get(leafKey.getAccountKey()));
			} finally {
				leaf.close();
				hub.close();
			}
		}
	}
}
