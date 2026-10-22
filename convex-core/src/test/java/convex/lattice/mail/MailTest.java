package convex.lattice.mail;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import convex.auth.did.DID;
import convex.core.crypto.AKeyPair;
import convex.core.crypto.ECIES;
import convex.core.data.ABlob;
import convex.core.data.ACell;
import convex.core.data.AHashMap;
import convex.core.data.AString;
import convex.core.data.Hash;
import convex.core.data.Index;
import convex.core.data.Keyword;
import convex.core.data.Maps;
import convex.core.data.SignedData;
import convex.core.data.Strings;
import convex.core.data.prim.CVMLong;
import convex.core.exceptions.BadFormatException;
import convex.lattice.LatticeApplication;
import convex.lattice.Lattice;
import convex.lattice.LatticeContext;
import convex.lattice.LatticeHost;
import convex.lattice.cursor.Cursors;
import convex.lattice.cursor.Root;
import convex.lattice.generic.KeyedLattice;

/** The mail components: atomic sends, open, receive and hosting. */
public class MailTest {

	static final AKeyPair ALICE=AKeyPair.createSeeded(42001);
	static final AKeyPair BOB=AKeyPair.createSeeded(42002);
	static final AKeyPair CAROL=AKeyPair.createSeeded(42003);

	static final AString ALICE_DID=DID.forKey(ALICE.getAccountKey());
	static final AString BOB_DID=DID.forKey(BOB.getAccountKey());
	static final AString CAROL_DID=DID.forKey(CAROL.getAccountKey());

	static final ECIES.Decryptor BOB_READS=ECIES.createDecryptor(BOB);
	static final ECIES.Decryptor ALICE_READS=ECIES.createDecryptor(ALICE);

	static LatticeContext context(AKeyPair key, long time) {
		return LatticeContext.create(CVMLong.create(time),key);
	}

	static MailEnvelope aliceToBob(long issuedAt, String text) {
		return MailEnvelope.seal(ALICE,BOB_DID,BOB.getAccountKey(),ALICE_DID,issuedAt,
			Maps.of(MailEnvelope.CONTENT,Strings.create(text)));
	}

	@SuppressWarnings("unchecked")
	static String content(MailMessage message) {
		return ((AHashMap<Keyword,ACell>)message.payload()).get(MailEnvelope.CONTENT).toString();
	}

	/** A batch send commits all recipient entries together; no send state lives on the draft instance. */
	@Test void sendsCommitTogetherAndSurviveRebinding() {
		Mail mail=Mail.create(context(ALICE,1000));
		var first=aliceToBob(1000,"one");
		var second=MailEnvelope.plain(ALICE,CAROL_DID,ALICE_DID,1001,Strings.create("two"));
		var before=mail.cursor().get();
		mail.transact(draft -> {
			draft.send(first);
			draft.send(second);
			assertSame(before,mail.cursor().get());
		});
		assertEquals(2,mail.cursor().get().count());
		Mail rebound=mail.withCursor(mail.cursor());
		assertEquals(List.of(first),rebound.mailbox(BOB_DID).deposited());
		assertEquals(List.of(second),rebound.mailbox(CAROL_DID).deposited());
		// A fresh component over a saved snapshot needs no separate sender state.
		Mail restored=Mail.connect(Cursors.root(mail.cursor().get()),Mail.LATTICE,mail.getContext());
		assertEquals(List.of(first),restored.mailbox(BOB_DID).deposited());
		var committed=mail.cursor().get();
		mail.send(first);
		assertSame(committed,mail.cursor().get(),"repeated sends must not duplicate a deposit");
	}

	@Test void abortedSendsLeaveNoStateToPublish() {
		Mail mail=Mail.create(context(ALICE,1000));
		var envelope=aliceToBob(1000,"abandoned");
		var before=mail.cursor().get();
		assertThrows(IllegalStateException.class,() -> mail.transact(draft -> {
			draft.send(envelope);
			throw new IllegalStateException("abort");
		}));
		mail.sync();
		assertSame(before,mail.cursor().get());
		assertTrue(mail.mailbox(BOB_DID).deposited().isEmpty());
	}

	/** A forced CAS conflict retries the same envelope without duplicating it or leaking the failed attempt. */
	@Test void retryKeepsOnlyCommittedSends() {
		var envelope=aliceToBob(1000,"retry");
		var abandoned=aliceToBob(1001,"first attempt only");
		var concurrent=MailEnvelope.plain(ALICE,CAROL_DID,ALICE_DID,1002,Strings.create("concurrent"));
		var commits=new AtomicInteger();
		var root=new Root<AHashMap<ACell,Index<ABlob,SignedData<ACell>>>>(Mail.LATTICE.zero()) {
			@Override public boolean compareAndSet(AHashMap<ACell,Index<ABlob,SignedData<ACell>>> expected,
					AHashMap<ACell,Index<ABlob,SignedData<ACell>>> value) {
				if (commits.incrementAndGet()==1) {
					// Another writer deposits before the first transaction can commit.
					super.set(expected.assoc(CAROL_DID,Index.create(concurrent.key(),concurrent.signed())));
					return false;
				}
				return super.compareAndSet(expected,value);
			}
		};
		Mail mail=Mail.connect(root,Mail.LATTICE,context(ALICE,1000));
		var attempts=new AtomicInteger();
		mail.transact(draft -> {
			draft.send(envelope);
			if (attempts.incrementAndGet()==1) draft.send(abandoned);
		});
		assertEquals(2,attempts.get());
		assertEquals(List.of(envelope),mail.mailbox(BOB_DID).deposited());
		assertEquals(List.of(concurrent),mail.mailbox(CAROL_DID).deposited());
	}

	/** The mailbox is the sole send state; sync cannot recreate a deliberately removed deposit. */
	@Test void liveSyncDoesNotRecreateRemovedDeposits() {
		Mail mail=Mail.create(context(ALICE,1000));
		mail.send(aliceToBob(1000,"removed"));
		var empty=Mail.LATTICE.zero();
		mail.cursor().set(empty);
		mail.sync();
		assertSame(empty,mail.cursor().get());
	}

	/** Fork publication reconciles sends and receipts through the same lattice entry. */
	@Test void forkedSendsPublishOnceAndCannotResurrectReceiptedMail() {
		Mail mail=Mail.create(context(ALICE,1000));
		var envelope=aliceToBob(1000,"fork");
		var fork=mail.fork();
		fork.send(envelope);
		assertTrue(mail.mailbox(BOB_DID).deposited().isEmpty());
		fork.sync();
		assertEquals(List.of(envelope),mail.mailbox(BOB_DID).deposited());
		Mail bob=Mail.connect(mail.cursor(),Mail.LATTICE,context(BOB,2000));
		bob.mailbox(BOB_DID).receive(envelope);
		var next=aliceToBob(1001,"next");
		fork.send(next);
		fork.sync();
		assertTrue(mail.mailbox(BOB_DID).isReceived(envelope));
		assertEquals(List.of(next),mail.mailbox(BOB_DID).deposited());
		assertSame(mail.cursor().get(),fork.sync());
	}

	@Test
	public void testSendOpenReceive() {
		// One region shared by both parties: a node holding Bob's mailbox
		Mail alice=Mail.create(context(ALICE,1000));
		MailEnvelope envelope=aliceToBob(1000,"hello bob");
		Hash hash=alice.send(envelope);
		assertEquals(envelope.hash(),hash);
		assertEquals(List.of(envelope),alice.mailbox(BOB_DID).deposited());

		Mail bob=Mail.connect(alice.cursor(),Mail.LATTICE,context(BOB,2000));
		Mailbox inbox=bob.mailbox(BOB_DID);
		assertEquals(List.of(envelope),inbox.deposited());

		List<MailMessage> messages=inbox.open(BOB_READS);
		assertEquals(1,messages.size());
		MailMessage message=messages.get(0);
		assertEquals(hash,message.hash());
		assertEquals(ALICE_DID,message.from());
		assertTrue(message.fromAuthorised());
		assertTrue(message.encrypted());
		assertEquals("hello bob",content(message));

		// Nobody else can open it
		assertTrue(inbox.open(ALICE_READS).isEmpty());

		List<MailReceipt> receipts=inbox.receive(inbox.deposited());
		assertEquals(1,receipts.size());
		assertEquals(BOB.getAccountKey(),receipts.get(0).signed().getAccountKey());
		assertTrue(inbox.deposited().isEmpty());
		assertTrue(inbox.isReceived(envelope));
		assertTrue(alice.mailbox(BOB_DID).isReceived(envelope));

		// A later deposit of the same envelope is absorbed by the receipt
		alice.mailbox(BOB_DID).deposit(envelope);
		assertTrue(inbox.deposited().isEmpty());
		alice.send(envelope);
		assertTrue(alice.mailbox(BOB_DID).isReceived(envelope));
		assertEquals(1,inbox.receipts().size());
	}

	@Test
	public void testMisfiledReceiptDoesNotConfirmDelivery() {
		// Alice sends two messages; Bob receipts the first
		Mail alice=Mail.create(context(ALICE,1000));
		MailEnvelope first=aliceToBob(1000,"one");
		MailEnvelope second=aliceToBob(1001,"two");
		alice.send(first);
		alice.send(second);
		Mail bob=Mail.connect(alice.cursor(),Mail.LATTICE,context(BOB,2000));
		MailReceipt receipt=bob.mailbox(BOB_DID).receive(first).get(0);
		assertTrue(alice.mailbox(BOB_DID).isReceived(first));
		assertEquals(List.of(second),alice.mailbox(BOB_DID).deposited());

		// A peer files Bob's genuine receipt under the second message's key. The register
		// is bound to its key, so the receipt is invalid there and the message survives
		AHashMap<ACell,Index<ABlob,SignedData<ACell>>> attack=
			Maps.of(BOB_DID,Index.create(second.key(),receipt.signed()));
		AHashMap<ACell,Index<ABlob,SignedData<ACell>>> before=alice.cursor().get();
		assertSame(before,alice.cursor().updateAndGet(own -> Mail.LATTICE.merge(own,attack)));
		assertEquals(List.of(second),bob.mailbox(BOB_DID).deposited());
		assertFalse(bob.mailbox(BOB_DID).isReceived(second));
		assertTrue(alice.mailbox(BOB_DID).isReceived(first));
		assertEquals(List.of(second),alice.mailbox(BOB_DID).deposited());

		// Nor does it take root in an empty mailbox
		assertEquals(0,Mail.LATTICE.merge(Mail.LATTICE.zero(),attack).get(BOB_DID).count());
	}

	@Test
	public void testReceiveNeedsAuthorisedSigner() {
		Mail alice=Mail.create(context(ALICE,1000));
		MailEnvelope envelope=aliceToBob(1000,"hello bob");
		alice.send(envelope);
		Mailbox bobsInboxSeenByAlice=alice.mailbox(BOB_DID);
		assertThrows(IllegalStateException.class,() -> bobsInboxSeenByAlice.receive(envelope));
		assertEquals(1,bobsInboxSeenByAlice.deposited().size());
	}

	@Test
	public void testRewrappedCiphertextIsRejected() {
		MailEnvelope genuine=aliceToBob(1000,"hello bob");
		// Carol lifts Alice's ciphertext into an envelope claiming to be from Carol
		MailEnvelope rewrapped=MailEnvelope.create(CAROL,BOB_DID,CAROL_DID,1000,true,genuine.body());
		assertThrows(BadFormatException.class,() -> rewrapped.open(BOB_READS));

		Mail mail=Mail.create(context(BOB,2000));
		mail.mailbox(BOB_DID).deposit(rewrapped);
		mail.mailbox(BOB_DID).deposit(genuine);
		List<MailMessage> messages=mail.mailbox(BOB_DID).open(BOB_READS);
		assertEquals(1,messages.size());
		assertEquals(genuine.hash(),messages.get(0).hash());

		// Carol re-wraps the ciphertext claiming to be Alice: the binding holds, so it
		// opens, but her signature is not authorised for Alice's DID
		MailEnvelope impersonating=MailEnvelope.create(CAROL,BOB_DID,ALICE_DID,1000,true,genuine.body());
		mail.mailbox(BOB_DID).deposit(impersonating);
		messages=mail.mailbox(BOB_DID).open(BOB_READS);
		assertEquals(2,messages.size());
		MailMessage forged=messages.stream().filter(m -> m.hash().equals(impersonating.hash())).findFirst().orElseThrow();
		assertEquals(ALICE_DID,forged.from());
		assertEquals(CAROL.getAccountKey(),forged.signer());
		assertFalse(forged.fromAuthorised());
		MailMessage real=messages.stream().filter(m -> m.hash().equals(genuine.hash())).findFirst().orElseThrow();
		assertTrue(real.fromAuthorised());
	}

	@Test
	public void testUnattributedEnvelope() {
		AKeyPair ephemeral=AKeyPair.generate();
		MailEnvelope envelope=MailEnvelope.seal(ephemeral,BOB_DID,BOB.getAccountKey(),null,1000,
			Maps.of(MailEnvelope.CONTENT,Strings.create("anonymous")));
		assertNull(envelope.from());

		Mail mail=Mail.create(context(BOB,2000));
		mail.mailbox(BOB_DID).deposit(envelope);
		List<MailMessage> messages=mail.mailbox(BOB_DID).open(BOB_READS);
		assertEquals(1,messages.size());
		assertNull(messages.get(0).from());
		assertFalse(messages.get(0).fromAuthorised());
		assertEquals(ephemeral.getAccountKey(),messages.get(0).signer());
		assertEquals("anonymous",content(messages.get(0)));

		// A plain body needs no decryptor
		MailEnvelope plain=MailEnvelope.plain(ALICE,BOB_DID,ALICE_DID,1001,Strings.create("public notice"));
		assertFalse(plain.encrypted());
		mail.mailbox(BOB_DID).deposit(plain);
		assertEquals(1,mail.mailbox(BOB_DID).open().size());
		assertEquals(2,mail.mailbox(BOB_DID).open(BOB_READS).size());
	}

	@Test
	public void testTwoRootsConverge() {
		Mail alice=Mail.create(context(ALICE,1000));
		Mail bob=Mail.create(context(BOB,2000));
		MailEnvelope envelope=aliceToBob(1000,"hello bob");
		alice.send(envelope);

		// Bob's node merges Alice's region: the envelope arrives
		bob.cursor().updateAndGet(own -> Mail.LATTICE.merge(own,alice.cursor().get()));
		Mailbox inbox=bob.mailbox(BOB_DID);
		assertEquals(1,inbox.open(BOB_READS).size());
		inbox.receive(inbox.deposited());

		// Alice's node merges Bob's region: the receipt replaces the body in the same entry
		assertEquals(List.of(envelope),alice.mailbox(BOB_DID).deposited());
		alice.cursor().updateAndGet(own -> Mail.LATTICE.merge(own,bob.cursor().get()));
		assertTrue(alice.mailbox(BOB_DID).isReceived(envelope));
		assertTrue(alice.mailbox(BOB_DID).deposited().isEmpty());
		assertEquals(1,alice.mailbox(BOB_DID).receipts().size());

		// Live sync leaves the receipt unchanged
		alice.sync();
		assertEquals(bob.cursor().get(),alice.cursor().get());
	}

	/** A root application over an in-memory root, as a host would hold it. */
	private static final class TestRoot extends LatticeApplication<Index<Keyword,ACell>> {
		TestRoot(KeyedLattice lattice) { super(lattice,Cursors.root(lattice.zero())); }
		@Override public Index<Keyword,ACell> merge(Index<Keyword,ACell> other) {
			return cursor.updateAndGet(own -> getLattice().merge(getContext(),own,other));
		}
	}

	/** A root application with private working state over a host, as a node holds it. */
	private static final class HostedRoot extends LatticeApplication<Index<Keyword,ACell>> {
		HostedRoot(KeyedLattice lattice, LatticeHost<Index<Keyword,ACell>> host) { super(lattice,host); }
		@Override public Index<Keyword,ACell> merge(Index<Keyword,ACell> other) {
			return cursor.updateAndGet(own -> getLattice().merge(getContext(),own,other));
		}
	}

	@Test
	public void testHostedApplicationPublishesToHost() {
		KeyedLattice root=KeyedLattice.create(Mail.KEY_MAIL,Mail.LATTICE);
		LatticeHost<Index<Keyword,ACell>> host=LatticeHost.create();
		HostedRoot app=new HostedRoot(root,host);
		host.configureApplications(List.of(app));
		Mail mail=Mail.connect(app,Mail.LATTICE,context(ALICE,1000));
		mail.send(aliceToBob(1000,"hello bob"));
		assertNotNull(app.cursor().get().get(Mail.KEY_MAIL),"working state holds the region");
		app.sync();
		assertNotNull(host.cursor().get().get(Mail.KEY_MAIL),"host root holds the region after sync");
		host.sync();
		assertNotNull(host.cursor().get().get(Mail.KEY_MAIL),"host root still holds the region");
	}

	@Test
	public void testHostedRegion() {
		KeyedLattice root=Lattice.ROOT.addLattice(Mail.KEY_MAIL,Mail.LATTICE);
		TestRoot app=new TestRoot(root);
		Mail mail=Mail.connect(app,Mail.LATTICE,context(ALICE,1000));
		MailEnvelope envelope=aliceToBob(1000,"hello bob");
		mail.send(envelope);
		app.sync();

		Index<Keyword,ACell> published=app.cursor().get();
		assertNotNull(published.get(Mail.KEY_MAIL));
		@SuppressWarnings("unchecked")
		AHashMap<ACell,ACell> region=(AHashMap<ACell,ACell>)published.get(Mail.KEY_MAIL);
		assertNotNull(region.get(BOB_DID));

		Mail bob=Mail.connect(app,Mail.LATTICE,context(BOB,2000));
		Mailbox inbox=bob.mailbox(BOB_DID);
		assertEquals("hello bob",content(inbox.open(BOB_READS).get(0)));
		inbox.receive(inbox.deposited());
		app.sync();
		assertTrue(mail.mailbox(BOB_DID).deposited().isEmpty());
	}
}
