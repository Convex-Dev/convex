package convex.lattice.mail;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BinaryOperator;

import org.junit.jupiter.api.Test;

import convex.auth.did.DID;
import convex.auth.did.DIDKeyAuthorizer;
import convex.core.crypto.AKeyPair;
import convex.core.crypto.Ed25519Signature;
import convex.core.data.ABlob;
import convex.core.data.ACell;
import convex.core.data.AHashMap;
import convex.core.data.AString;
import convex.core.data.Blob;
import convex.core.data.Index;
import convex.core.data.Maps;
import convex.core.data.Ref;
import convex.core.data.SignedData;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMBool;
import convex.core.data.prim.CVMLong;
import convex.core.exceptions.MissingDataException;
import convex.core.store.MemoryStore;
import convex.lattice.ComponentsTest;
import convex.lattice.cursor.Root;

/** Merge rules of the mail region: the entry register, the mailbox index and the DID map. */
public class MailMergeTest {

	static final AKeyPair ALICE=AKeyPair.createSeeded(41001);
	static final AKeyPair BOB=AKeyPair.createSeeded(41002);
	static final AKeyPair BOB_PHONE=AKeyPair.createSeeded(41003);
	static final AKeyPair CAROL=AKeyPair.createSeeded(41004);

	static final AString ALICE_DID=DID.forKey(ALICE.getAccountKey());
	static final AString BOB_DID=DID.forKey(BOB.getAccountKey());

	static MailEnvelope envelope(AKeyPair signer, AString from, AString to, AKeyPair recipient, long issuedAt, String text) {
		return MailEnvelope.seal(signer,to,recipient.getAccountKey(),from,issuedAt,
			Maps.of(MailEnvelope.CONTENT,Strings.create(text)));
	}

	static MailEnvelope toBob(long issuedAt, String text) {
		return envelope(ALICE,ALICE_DID,BOB_DID,BOB,issuedAt,text);
	}

	static Index<ABlob,SignedData<ACell>> entries(Object... items) {
		Index<ABlob,SignedData<ACell>> index=Index.none();
		for (Object item:items) {
			if (item instanceof MailEnvelope e) index=index.assoc(e.key(),e.signed());
			else if (item instanceof MailReceipt r) index=index.assoc(r.key(),r.signed());
			else throw new IllegalArgumentException("Not an entry: "+item);
		}
		return index;
	}

	static MailReceipt receipt(AKeyPair signer, AString did, MailEnvelope envelope) {
		return MailReceipt.sign(signer,did,envelope.hash(),envelope.issuedAt());
	}

	/** Bob's mailbox holding a value, admitting receipts by the given rule. */
	static Mailbox bobsMailbox(DIDKeyAuthorizer authorizer, Index<ABlob,SignedData<ACell>> held) {
		return new Mailbox(new Root<>(held),BOB_DID,authorizer,null);
	}

	/** Merges an incoming value into Bob's mailbox holding another, under the did:key-only rule. */
	static Index<ABlob,SignedData<ACell>> merge(Index<ABlob,SignedData<ACell>> own, Index<ABlob,SignedData<ACell>> other) {
		return bobsMailbox(DIDKeyAuthorizer.CONVEX,own).merge(other);
	}

	/** Merges an incoming region into a region holding another, under the did:key-only rule. */
	static AHashMap<ACell,Index<ABlob,SignedData<ACell>>> mergeRegion(AHashMap<ACell,Index<ABlob,SignedData<ACell>>> own,
			AHashMap<ACell,Index<ABlob,SignedData<ACell>>> other) {
		return Mail.connect(new Root<>(own),null).merge(other);
	}

	@Test
	public void testEnvelopeAndReceiptRoundTrip() {
		MailEnvelope e=toBob(1000,"hello");
		assertEquals(e,MailEnvelope.parse(e.signed()));
		assertEquals(BOB_DID,e.to());
		assertEquals(ALICE_DID,e.from());
		assertEquals(ALICE.getAccountKey(),e.signer());
		assertTrue(e.encrypted());
		assertEquals(Mailbox.KEY_LENGTH,e.key().count());
		assertNull(MailReceipt.parse(e.signed()));

		MailReceipt r=receipt(BOB,BOB_DID,e);
		assertEquals(e.key(),r.key());
		assertEquals(e.hash(),r.hash());
		assertNull(MailEnvelope.parse(r.signed()));

		// Keys order by issued-at first
		MailEnvelope later=toBob(2000,"later");
		assertTrue(e.key().compareTo(later.key())<0);
	}

	@Test
	public void testReceiptReplacesEnvelopeInBothOrders() {
		MailEnvelope e=toBob(1000,"hello");
		Index<ABlob,SignedData<ACell>> deposited=entries(e);
		Index<ABlob,SignedData<ACell>> received=entries(receipt(BOB,BOB_DID,e));

		assertEquals(received,merge(deposited,received));
		assertEquals(received,merge(received,deposited));
		// A receipt that arrives first is a tombstone which absorbs the envelope
		Index<ABlob,SignedData<ACell>> tombstone=merge(Index.none(),received);
		assertEquals(received,tombstone);
		assertSame(tombstone,merge(tombstone,deposited));
		assertEquals(1,merge(deposited,received).count());
	}

	@Test
	public void testTwoReceiptsConvergeToLowerHash() {
		// Bob's DID has two authorised devices under this rule
		DIDKeyAuthorizer twoDevices=(did,key) -> did.equals(BOB_DID)
			&& (key.equals(BOB.getAccountKey()) || key.equals(BOB_PHONE.getAccountKey()));
		BinaryOperator<Index<ABlob,SignedData<ACell>>> mailbox=(own,other) -> bobsMailbox(twoDevices,own).merge(other);

		MailEnvelope e=toBob(1000,"hello");
		MailReceipt r1=receipt(BOB,BOB_DID,e);
		MailReceipt r2=receipt(BOB_PHONE,BOB_DID,e);
		SignedData<ACell> expected=(r1.signed().getHash().compareTo(r2.signed().getHash())<=0)?r1.signed():r2.signed();

		Index<ABlob,SignedData<ACell>> a=entries(r1);
		Index<ABlob,SignedData<ACell>> b=entries(r2);
		assertEquals(expected,mailbox.apply(a,b).get(e.key()));
		assertEquals(expected,mailbox.apply(b,a).get(e.key()));
		assertEquals(expected,mailbox.apply(mailbox.apply(entries(e),a),b).get(e.key()));

		// The phone's receipt is not accepted under the did:key-only rule
		assertEquals(0,merge(Index.none(),b).count());
	}

	@Test
	public void testHeldEntriesAreNotCheckedAgain() {
		// Bob's phone signs receipts until its key is revoked
		AtomicBoolean phoneAuthorised=new AtomicBoolean(true);
		DIDKeyAuthorizer revocable=(did,key) -> did.equals(BOB_DID)
			&& (key.equals(BOB.getAccountKey()) || (phoneAuthorised.get() && key.equals(BOB_PHONE.getAccountKey())));
		MailEnvelope e1=toBob(1000,"one");
		MailEnvelope e2=toBob(2000,"two");
		MailReceipt byPhone=receipt(BOB_PHONE,BOB_DID,e1);

		Mailbox mailbox=bobsMailbox(revocable,entries(e1));
		Mailbox fork=mailbox.fork();
		fork.merge(entries(byPhone));
		mailbox.deposit(e2);
		phoneAuthorised.set(false);

		// A fork reconciles with its parent without checking either side again
		fork.sync();
		Index<ABlob,SignedData<ACell>> held=mailbox.value();
		assertEquals(entries(byPhone,e2),held);
		// A redelivered envelope does not displace the receipt accepted before the revocation
		assertSame(held,mailbox.merge(entries(e1)));
		// The revoked key's receipts are refused from now on
		assertSame(held,mailbox.merge(entries(receipt(BOB_PHONE,BOB_DID,e2))));
	}

	@Test
	public void testMergeLaws() {
		MailEnvelope e1=toBob(1000,"one");
		MailEnvelope e2=toBob(2000,"two");
		MailEnvelope e3=toBob(3000,"three");
		Index<ABlob,SignedData<ACell>> v1=entries(e1,e2);
		Index<ABlob,SignedData<ACell>> v2=entries(receipt(BOB,BOB_DID,e1),e3);
		ComponentsTest.doJoinTests(bobsMailbox(DIDKeyAuthorizer.CONVEX,null),v1,v2);
		assertSame(v1,merge(v1,Index.none()));

		Index<ABlob,SignedData<ACell>> merged=merge(v1,v2);
		assertEquals(3,merged.count());
		assertNotNull(MailReceipt.parse(merged.get(e1.key())));
		assertNotNull(MailEnvelope.parse(merged.get(e2.key())));
		assertNotNull(MailEnvelope.parse(merged.get(e3.key())));

		// The region map, keyed by DID
		MailEnvelope toAlice=envelope(BOB,BOB_DID,ALICE_DID,ALICE,1500,"reply");
		AHashMap<ACell,Index<ABlob,SignedData<ACell>>> m1=Maps.of(BOB_DID,v1);
		AHashMap<ACell,Index<ABlob,SignedData<ACell>>> m2=Maps.of(BOB_DID,v2,ALICE_DID,entries(toAlice));
		ComponentsTest.doJoinTests(Mail.create(),m1,m2);
		assertSame(m1,mergeRegion(m1,Maps.empty()));
		assertEquals(merged,mergeRegion(m1,m2).get(BOB_DID));
	}

	@Test
	public void testAllMergeOrdersAgree() {
		MailEnvelope e1=toBob(1000,"one");
		MailEnvelope e2=toBob(2000,"two");
		MailEnvelope e3=toBob(3000,"three");
		Index<ABlob,SignedData<ACell>> a=entries(e1,e2);
		Index<ABlob,SignedData<ACell>> b=entries(receipt(BOB,BOB_DID,e1));
		Index<ABlob,SignedData<ACell>> c=entries(e3,receipt(BOB,BOB_DID,e2));

		Index<ABlob,SignedData<ACell>> expected=fold(List.of(a,b,c));
		assertEquals(expected,fold(List.of(a,c,b)));
		assertEquals(expected,fold(List.of(b,a,c)));
		assertEquals(expected,fold(List.of(b,c,a)));
		assertEquals(expected,fold(List.of(c,a,b)));
		assertEquals(expected,fold(List.of(c,b,a)));
		assertEquals(expected,merge(a,merge(b,c)));
		assertEquals(expected,merge(merge(a,b),c));

		assertEquals(3,expected.count());
		assertNotNull(MailReceipt.parse(expected.get(e1.key())));
		assertNotNull(MailReceipt.parse(expected.get(e2.key())));
		assertNotNull(MailEnvelope.parse(expected.get(e3.key())));
	}

	/** Delivers each value in turn to one mailbox. */
	private static Index<ABlob,SignedData<ACell>> fold(List<Index<ABlob,SignedData<ACell>>> slots) {
		Mailbox mailbox=bobsMailbox(DIDKeyAuthorizer.CONVEX,null);
		for (Index<ABlob,SignedData<ACell>> slot:slots) mailbox.merge(slot);
		return mailbox.value();
	}

	@Test
	public void testRejectsUnauthorisedReceipt() {
		MailEnvelope e=toBob(1000,"hello");
		Index<ABlob,SignedData<ACell>> deposited=entries(e);
		Index<ABlob,SignedData<ACell>> forged=entries(receipt(CAROL,BOB_DID,e));

		// Dropped whether or not the envelope is already held
		assertEquals(0,merge(Index.none(),forged).count());
		assertSame(deposited,merge(deposited,forged));
	}

	@Test
	public void testRejectsReceiptBoundToAnotherMailbox() {
		MailEnvelope e=toBob(1000,"hello");
		// Bob signs a receipt naming Alice's mailbox; it is not valid in his own
		Index<ABlob,SignedData<ACell>> misbound=entries(receipt(BOB,ALICE_DID,e));
		assertEquals(0,merge(Index.none(),misbound).count());
		Index<ABlob,SignedData<ACell>> deposited=entries(e);
		assertSame(deposited,merge(deposited,misbound));
	}

	@Test
	public void testRejectsEnvelopeAddressedElsewhere() {
		MailEnvelope toAlice=envelope(BOB,BOB_DID,ALICE_DID,ALICE,1000,"for alice");
		Index<ABlob,SignedData<ACell>> misplaced=entries(toAlice);
		assertEquals(0,merge(Index.none(),misplaced).count());
	}

	@Test
	public void testRejectsBadSignature() {
		MailEnvelope e=toBob(1000,"hello");
		SignedData<ACell> tampered=SignedData.create(ALICE.getAccountKey(),Ed25519Signature.ZERO,e.signed().getValueRef());
		Index<ABlob,SignedData<ACell>> bad=Index.create(e.key(),tampered);
		assertEquals(0,merge(Index.none(),bad).count());

		MailReceipt r=receipt(BOB,BOB_DID,e);
		SignedData<ACell> tamperedReceipt=SignedData.create(BOB.getAccountKey(),Ed25519Signature.ZERO,r.signed().getValueRef());
		Index<ABlob,SignedData<ACell>> badReceipt=Index.create(r.key(),tamperedReceipt);
		Index<ABlob,SignedData<ACell>> deposited=entries(e);
		assertSame(deposited,merge(deposited,badReceipt));
	}

	@Test
	public void testMisfiledReceiptIsDropped() {
		// Bob's genuine receipt for one message, filed by a peer under another message's
		// key. Were it accepted there it would replace that message on every node and
		// read as its delivery; the key-bound register rejects it
		MailEnvelope e1=toBob(1000,"one");
		MailEnvelope e2=toBob(1001,"two");
		MailReceipt r1=receipt(BOB,BOB_DID,e1);
		Index<ABlob,SignedData<ACell>> deposited=entries(e2);
		Index<ABlob,SignedData<ACell>> misfiled=Index.create(e2.key(),r1.signed());

		assertEquals(0,merge(Index.none(),misfiled).count());
		assertSame(deposited,merge(deposited,misfiled));

		// Under its own key the same receipt replaces its own envelope and nothing else
		Index<ABlob,SignedData<ACell>> both=entries(e1,e2);
		Index<ABlob,SignedData<ACell>> merged=merge(both,entries(r1));
		assertEquals(2,merged.count());
		assertEquals(r1.signed(),merged.get(e1.key()));
		assertEquals(e2.signed(),merged.get(e2.key()));
	}

	@Test
	public void testMisfiledEnvelopeIsDropped() {
		// A valid envelope filed under another envelope's key would otherwise compete on
		// hash with the entry that belongs there, and could never be receipted
		MailEnvelope e1=toBob(1000,"one");
		MailEnvelope e2=toBob(1000,"two");
		Index<ABlob,SignedData<ACell>> honest=entries(e1);
		Index<ABlob,SignedData<ACell>> misfiled=Index.create(e1.key(),e2.signed());

		assertEquals(0,merge(Index.none(),misfiled).count());
		assertSame(honest,merge(honest,misfiled));

		// A key of the wrong shape has no register at all
		Index<ABlob,SignedData<ACell>> badKey=Index.create(Blob.fromHex("ff"),e1.signed());
		assertEquals(0,merge(Index.none(),badKey).count());
	}

	@Test
	public void testEmptyMailboxesMergeAlikeHeldOrIncoming() {
		AHashMap<ACell,Index<ABlob,SignedData<ACell>>> empty=Maps.empty();
		AHashMap<ACell,Index<ABlob,SignedData<ACell>>> emptyMailbox=Maps.of(BOB_DID,Index.none());
		assertEquals(mergeRegion(empty,emptyMailbox),mergeRegion(emptyMailbox,empty));
		ComponentsTest.doJoinTests(Mail.create(),emptyMailbox,Maps.of(BOB_DID,entries(toBob(1000,"hello"))));

		// A delivery whose every entry is rejected merges as the empty mailbox does
		AHashMap<ACell,Index<ABlob,SignedData<ACell>>> forged=Maps.of(BOB_DID,entries(receipt(CAROL,BOB_DID,toBob(1000,"hello"))));
		assertEquals(mergeRegion(empty,emptyMailbox),mergeRegion(empty,forged));
		ComponentsTest.doRefusalTests(Mail.create(),emptyMailbox,forged);
	}

	@Test
	public void testEntryDeliveryBindsKey() {
		MailEnvelope e1=toBob(1000,"one");
		MailEnvelope e2=toBob(1001,"two");
		Index<ABlob,SignedData<ACell>> empty=Index.none();
		Mailbox mailbox=bobsMailbox(DIDKeyAuthorizer.CONVEX,empty);

		// The register at one key accepts only entries that belong there
		assertSame(empty,mailbox.mergeAt(e2.signed(),e1.key()));
		assertSame(empty,mailbox.mergeAt(receipt(BOB,BOB_DID,e2).signed(),e1.key()));
		assertSame(empty,mailbox.mergeAt(e1.signed(),Blob.fromHex("ff")));
		assertSame(empty,mailbox.mergeAt(e1.signed(),BOB_DID));
		assertSame(empty,mailbox.mergeAt(e1.signed(),e1.key(),e1.key()));
		assertEquals(entries(e1),mailbox.mergeAt(e1.signed(),e1.key()));

		// An entry that is not signed data is dropped
		assertEquals(entries(e1),mailbox.mergeAt(Strings.create("junk"),e2.key()));
	}

	@Test
	public void testOversizedBodyIsDropped() {
		Blob big=Blob.createRandom(new Random(7),MailEnvelope.MAX_BODY_LENGTH+1);
		assertThrows(IllegalArgumentException.class,() -> MailEnvelope.plain(ALICE,BOB_DID,ALICE_DID,1000,big));

		// A hand-built oversized envelope is dropped by the merge before any verification
		SignedData<ACell> oversized=ALICE.signData(Vectors.of(MailEnvelope.CONTEXT,BOB_DID,ALICE_DID,
			CVMLong.create(1000),CVMBool.FALSE,big));
		ABlob key=Mailbox.key(1000,oversized.getHash());
		assertEquals(0,merge(Index.none(),Index.create(key,oversized)).count());
	}

	@Test
	public void testRegionDropsNonDidKeys() {
		MailEnvelope e=toBob(1000,"hello");
		AHashMap<ACell,Index<ABlob,SignedData<ACell>>> junk=Maps.of(
			Strings.create("not a did"),entries(e),
			Strings.create("did:key"),entries(e));
		assertEquals(0,mergeRegion(Maps.empty(),junk).count());

		// Deliveries beneath a key route only to a mailbox
		Mail mail=Mail.create();
		mail.mergeAt(entries(e),Strings.create("nope"));
		mail.mergeAt(e.signed(),Strings.create("nope"),e.key());
		assertEquals(0,mail.value().count());
		mail.mergeAt(entries(e),BOB_DID);
		assertEquals(List.of(e),mail.mailbox(BOB_DID).deposited());
		Mail other=Mail.create();
		other.mergeAt(e.signed(),BOB_DID,e.key());
		assertEquals(mail.value(),other.value());
	}

	@SuppressWarnings({"unchecked","rawtypes"})
	@Test
	public void testWrongKindsAreDroppedAlone() {
		MailEnvelope e1=toBob(1000,"one");
		MailEnvelope e2=toBob(1001,"two");
		AHashMap<ACell,Index<ABlob,SignedData<ACell>>> expected=Maps.of(BOB_DID,entries(e1));

		// A mailbox that is not an index is dropped, and the other mailboxes merge
		AHashMap badMailbox=Maps.of(ALICE_DID,Strings.create("not an index"),BOB_DID,entries(e1));
		assertEquals(expected,mergeRegion(Maps.empty(),badMailbox));

		// An entry that is not signed data is dropped, and the other entries merge
		AHashMap badEntry=Maps.of(BOB_DID,((Index)entries(e1)).assoc(e2.key(),Strings.create("junk")));
		assertEquals(expected,mergeRegion(Maps.empty(),badEntry));

		// A delivered region or mailbox of the wrong kind is dropped
		Mail mail=Mail.create();
		AHashMap<ACell,Index<ABlob,SignedData<ACell>>> before=mail.value();
		assertSame(before,mail.mergeAt(Strings.create("not a map")));
		assertSame(before,mail.mergeAt(Strings.create("not an index"),BOB_DID));
		assertSame(before,mail.value());
	}

	@Test
	public void testIncompleteLocalDataFailsTheMergeAndChangesNothing() {
		MailEnvelope e1=toBob(1000,"one");
		MailEnvelope e2=toBob(1001,"two");
		// An entry whose payload this store cannot read is not invalid input
		SignedData<ACell> incomplete=SignedData.create(ALICE.getAccountKey(),Ed25519Signature.ZERO,
			Ref.forHash(e2.signed().getValueRef().getHash(),new MemoryStore()));
		Index<ABlob,SignedData<ACell>> held=entries(e1);
		Mailbox mailbox=bobsMailbox(DIDKeyAuthorizer.CONVEX,held);
		assertThrows(MissingDataException.class,() -> mailbox.merge(Index.create(e2.key(),incomplete)));
		assertSame(held,mailbox.value());
	}
}
