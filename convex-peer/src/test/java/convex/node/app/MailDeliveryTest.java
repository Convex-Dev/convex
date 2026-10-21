package convex.node.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import convex.api.ConvexRemote;
import convex.auth.did.DID;
import convex.core.crypto.AKeyPair;
import convex.core.crypto.ECIES;
import convex.core.data.ACell;
import convex.core.data.AHashMap;
import convex.core.data.AString;
import convex.core.data.Index;
import convex.core.data.Keyword;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.etch.EtchStore;
import convex.lattice.LatticeApplication;
import convex.lattice.LatticeContext;
import convex.lattice.LatticeHost;
import convex.lattice.generic.KeyedLattice;
import convex.lattice.mail.Mail;
import convex.lattice.mail.MailEnvelope;
import convex.lattice.mail.MailMessage;
import convex.lattice.mail.Mailbox;
import convex.node.LatticeListener;
import convex.node.LatticePropagator;
import convex.node.NodeConfig;
import convex.node.NodeServer;

/**
 * Mail delivered between two nodes: Alice deposits on her node, Bob's node pulls only
 * his mailbox path, Bob receipts, and Alice's node pulls the receipt back, which replaces
 * the body and clears her outbox. Every step waits on a connection, pull or announce
 * future; nothing sleeps.
 */
class MailDeliveryTest {
	private static final KeyedLattice ROOT=KeyedLattice.create(Mail.KEY_MAIL,Mail.LATTICE);
	private static final AKeyPair ALICE_NODE=AKeyPair.createSeeded(9290);
	private static final AKeyPair BOB_NODE=AKeyPair.createSeeded(9291);
	private static final AKeyPair ALICE=AKeyPair.createSeeded(9292);
	private static final AKeyPair BOB=AKeyPair.createSeeded(9293);
	private static final AKeyPair CAROL=AKeyPair.createSeeded(9294);
	private static final AString ALICE_DID=DID.forKey(ALICE.getAccountKey());
	private static final AString BOB_DID=DID.forKey(BOB.getAccountKey());
	private static final AString CAROL_DID=DID.forKey(CAROL.getAccountKey());

	/**
	 * The application delegates every incoming value, a pulled mailbox included, to its
	 * lattice; a mail replication policy would first project it to the mailboxes a node
	 * desires.
	 */
	static final class MailApp extends LatticeApplication<Index<Keyword,ACell>> {
		MailApp(LatticeHost<Index<Keyword,ACell>> host) { super(ROOT,host); }
		Mail mail(AKeyPair user) { return Mail.connect(this,Mail.LATTICE,LatticeContext.create(null,user)); }
		@Override public Index<Keyword,ACell> merge(Index<Keyword,ACell> other) {
			return cursor.updateAndGet(own -> getLattice().merge(getContext(),own,other));
		}
	}

	/** The caller owns networking and stores; the application itself has no workers. */
	static final class RunningNode implements AutoCloseable {
		final LatticeHost<Index<Keyword,ACell>> host;
		final MailApp app;
		final NodeServer<Index<Keyword,ACell>> node;
		final LatticePropagator group;
		final LatticeListener listener;

		RunningNode(EtchStore store,AKeyPair transportKey) throws Exception {
			host=LatticeHost.open(store);
			app=new MailApp(host);
			host.configureApplications(List.of(app));
			var config=NodeConfig.localNetwork();
			node=new NodeServer<>(host,config);
			group=new LatticePropagator(store);
			group.setTransportKeyPair(transportKey);
			node.addPropagator(group);
			listener=new LatticeListener(config);
			listener.registerPropagator(group);
			listener.setSelector(connection -> group);
			node.launch();
			listener.launch();
		}

		/** Waits until the propagator has announced the host's current root. */
		void awaitAnnounced() throws Exception {
			long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
			while (true) {
				var next=group.nextAnnounce();
				if (host.cursor().get().equals(group.getLastAnnouncedValue())) break;
				next.get(Math.max(1,deadline-System.nanoTime()),TimeUnit.NANOSECONDS);
			}
		}

		/** Pulls one mailbox path from another running node and waits for the merge. */
		void pullMailbox(RunningNode from,AKeyPair fromKey,AString did) throws Exception {
			try (var peer=ConvexRemote.connect(from.listener.getHostAddress())) {
				group.addPeer(fromKey.getAccountKey(),peer).get(10,TimeUnit.SECONDS);
				node.pullPath(group,peer,true,Mail.KEY_MAIL,did).get(10,TimeUnit.SECONDS);
			}
		}

		/** The mail region in the application's working state, where incoming values land. */
		@SuppressWarnings("unchecked")
		AHashMap<ACell,ACell> region() {
			return (AHashMap<ACell,ACell>)app.cursor().get().get(Mail.KEY_MAIL);
		}

		/** The mail region in the published host root. */
		@SuppressWarnings("unchecked")
		AHashMap<ACell,ACell> publishedRegion() {
			return (AHashMap<ACell,ACell>)host.cursor().get().get(Mail.KEY_MAIL);
		}

		@Override public void close() throws Exception {
			try { listener.close(); }
			finally { node.close(); }
		}
	}

	@SuppressWarnings("unchecked")
	private static String content(MailMessage message) {
		return ((AHashMap<Keyword,ACell>)message.payload()).get(MailEnvelope.CONTENT).toString();
	}

	@Test void mailIsDeliveredAndReceiptedAcrossNodes() throws Exception {
		try (var aliceStore=EtchStore.createTemp("mail-alice");
				var bobStore=EtchStore.createTemp("mail-bob");
				var alice=new RunningNode(aliceStore,ALICE_NODE);
				var bob=new RunningNode(bobStore,BOB_NODE)) {
			Mail aliceMail=alice.app.mail(ALICE);
			MailEnvelope toBob=MailEnvelope.seal(ALICE,BOB_DID,BOB.getAccountKey(),ALICE_DID,1000,
				Maps.of(MailEnvelope.CONTENT,Strings.create("hello bob")));
			MailEnvelope toCarol=MailEnvelope.seal(ALICE,CAROL_DID,CAROL.getAccountKey(),ALICE_DID,1001,
				Maps.of(MailEnvelope.CONTENT,Strings.create("hello carol")));
			aliceMail.send(toBob);
			aliceMail.send(toCarol);
			assertNull(alice.publishedRegion());
			alice.app.sync();
			alice.host.sync();
			alice.awaitAnnounced();
			assertEquals(2,alice.publishedRegion().count());

			// Bob's node pulls only his mailbox: Carol's never reaches it. The pulled
			// value lands in Bob's application state and stays off his published root
			// until his application syncs.
			bob.pullMailbox(alice,ALICE_NODE,BOB_DID);
			assertEquals(1,bob.region().count());
			assertNotNull(bob.region().get(BOB_DID));
			assertNull(bob.region().get(CAROL_DID));
			assertNull(bob.publishedRegion());

			Mail bobMail=bob.app.mail(BOB);
			Mailbox inbox=bobMail.mailbox(BOB_DID);
			List<MailMessage> messages=inbox.open(ECIES.createDecryptor(BOB));
			assertEquals(1,messages.size());
			assertEquals(ALICE_DID,messages.get(0).from());
			assertTrue(messages.get(0).fromAuthorised());
			assertEquals("hello bob",content(messages.get(0)));

			inbox.receive(inbox.deposited());
			assertTrue(inbox.deposited().isEmpty());
			bob.app.sync();
			bob.host.sync();
			bob.awaitAnnounced();
			assertEquals(1,bob.publishedRegion().count());

			// Alice's node pulls the mailbox back: the receipt replaces the body
			assertEquals(Set.of(toBob.hash(),toCarol.hash()),aliceMail.outbox());
			alice.pullMailbox(bob,BOB_NODE,BOB_DID);
			assertEquals(Set.of(toCarol.hash()),aliceMail.outbox());
			assertTrue(aliceMail.mailbox(BOB_DID).deposited().isEmpty());
			assertEquals(1,aliceMail.mailbox(BOB_DID).receipts().size());
			assertEquals(1,aliceMail.mailbox(CAROL_DID).deposited().size());
		}
	}
}
