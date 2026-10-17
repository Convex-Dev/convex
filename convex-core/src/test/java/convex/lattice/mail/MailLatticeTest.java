package convex.lattice.mail;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Random;

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
import convex.core.data.SignedData;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMBool;
import convex.core.data.prim.CVMLong;
import convex.lattice.ALattice;
import convex.lattice.LatticeTest;

/** Merge rules of the mail region: the entry register, the mailbox index and the DID map. */
public class MailLatticeTest {

	static final AKeyPair ALICE=AKeyPair.createSeeded(41001);
	static final AKeyPair BOB=AKeyPair.createSeeded(41002);
	static final AKeyPair BOB_PHONE=AKeyPair.createSeeded(41003);
	static final AKeyPair CAROL=AKeyPair.createSeeded(41004);

	static final AString ALICE_DID=DID.forKey(ALICE.getAccountKey());
	static final AString BOB_DID=DID.forKey(BOB.getAccountKey());

	static final MailboxLattice BOB_MAILBOX=Mail.LATTICE.forMailbox(BOB_DID);

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

	@Test
	public void testEnvelopeAndReceiptRoundTrip() {
		MailEnvelope e=toBob(1000,"hello");
		assertEquals(e,MailEnvelope.parse(e.signed()));
		assertEquals(BOB_DID,e.to());
		assertEquals(ALICE_DID,e.from());
		assertEquals(ALICE.getAccountKey(),e.signer());
		assertTrue(e.encrypted());
		assertEquals(MailEntryLattice.KEY_LENGTH,e.key().count());
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

		assertEquals(received,BOB_MAILBOX.merge(deposited,received));
		assertEquals(received,BOB_MAILBOX.merge(received,deposited));
		// A receipt that arrives first is a tombstone which absorbs the envelope
		Index<ABlob,SignedData<ACell>> tombstone=BOB_MAILBOX.merge(BOB_MAILBOX.zero(),received);
		assertEquals(received,tombstone);
		assertSame(tombstone,BOB_MAILBOX.merge(tombstone,deposited));
		assertEquals(1,BOB_MAILBOX.merge(deposited,received).count());
	}

	@Test
	public void testTwoReceiptsConvergeToLowerHash() {
		// Bob's DID has two authorised devices under this rule
		DIDKeyAuthorizer twoDevices=(did,key) -> did.equals(BOB_DID)
			&& (key.equals(BOB.getAccountKey()) || key.equals(BOB_PHONE.getAccountKey()));
		MailboxLattice mailbox=MailLattice.create(twoDevices).forMailbox(BOB_DID);

		MailEnvelope e=toBob(1000,"hello");
		MailReceipt r1=receipt(BOB,BOB_DID,e);
		MailReceipt r2=receipt(BOB_PHONE,BOB_DID,e);
		SignedData<ACell> expected=(r1.signed().getHash().compareTo(r2.signed().getHash())<=0)?r1.signed():r2.signed();

		Index<ABlob,SignedData<ACell>> a=entries(r1);
		Index<ABlob,SignedData<ACell>> b=entries(r2);
		assertEquals(expected,mailbox.merge(a,b).get(e.key()));
		assertEquals(expected,mailbox.merge(b,a).get(e.key()));
		assertEquals(expected,mailbox.merge(mailbox.merge(entries(e),a),b).get(e.key()));

		// The phone's receipt is not accepted under the did:key-only rule
		assertEquals(0,BOB_MAILBOX.merge(BOB_MAILBOX.zero(),b).count());
	}

	@Test
	public void testLatticeLaws() {
		MailEnvelope e1=toBob(1000,"one");
		MailEnvelope e2=toBob(2000,"two");
		MailEnvelope e3=toBob(3000,"three");
		Index<ABlob,SignedData<ACell>> v1=entries(e1,e2);
		Index<ABlob,SignedData<ACell>> v2=entries(receipt(BOB,BOB_DID,e1),e3);
		LatticeTest.doLatticeTest(BOB_MAILBOX,v1,v2,e2.key());

		Index<ABlob,SignedData<ACell>> merged=BOB_MAILBOX.merge(v1,v2);
		assertEquals(3,merged.count());
		assertNotNull(MailReceipt.parse(merged.get(e1.key())));
		assertNotNull(MailEnvelope.parse(merged.get(e2.key())));
		assertNotNull(MailEnvelope.parse(merged.get(e3.key())));

		// The region map, keyed by DID
		MailEnvelope toAlice=envelope(BOB,BOB_DID,ALICE_DID,ALICE,1500,"reply");
		AHashMap<ACell,Index<ABlob,SignedData<ACell>>> m1=Maps.of(BOB_DID,v1);
		AHashMap<ACell,Index<ABlob,SignedData<ACell>>> m2=Maps.of(BOB_DID,v2,ALICE_DID,entries(toAlice));
		LatticeTest.doLatticeTest(Mail.LATTICE,m1,m2,BOB_DID);
		assertEquals(merged,Mail.LATTICE.merge(m1,m2).get(BOB_DID));
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
		assertEquals(expected,BOB_MAILBOX.merge(a,BOB_MAILBOX.merge(b,c)));
		assertEquals(expected,BOB_MAILBOX.merge(BOB_MAILBOX.merge(a,b),c));

		assertEquals(3,expected.count());
		assertNotNull(MailReceipt.parse(expected.get(e1.key())));
		assertNotNull(MailReceipt.parse(expected.get(e2.key())));
		assertNotNull(MailEnvelope.parse(expected.get(e3.key())));
	}

	private static Index<ABlob,SignedData<ACell>> fold(List<Index<ABlob,SignedData<ACell>>> slots) {
		Index<ABlob,SignedData<ACell>> result=BOB_MAILBOX.zero();
		for (Index<ABlob,SignedData<ACell>> slot:slots) result=BOB_MAILBOX.merge(result,slot);
		return result;
	}

	@Test
	public void testRejectsUnauthorisedReceipt() {
		MailEnvelope e=toBob(1000,"hello");
		Index<ABlob,SignedData<ACell>> deposited=entries(e);
		Index<ABlob,SignedData<ACell>> forged=entries(receipt(CAROL,BOB_DID,e));

		// Dropped whether or not the envelope is already held
		assertEquals(0,BOB_MAILBOX.merge(BOB_MAILBOX.zero(),forged).count());
		assertSame(deposited,BOB_MAILBOX.merge(deposited,forged));
		// A forged entry held locally cannot suppress the envelope either
		assertEquals(deposited,BOB_MAILBOX.merge(forged,deposited));
	}

	@Test
	public void testRejectsReceiptBoundToAnotherMailbox() {
		MailEnvelope e=toBob(1000,"hello");
		// Bob signs a receipt naming Alice's mailbox; it is not valid in his own
		Index<ABlob,SignedData<ACell>> misbound=entries(receipt(BOB,ALICE_DID,e));
		assertEquals(0,BOB_MAILBOX.merge(BOB_MAILBOX.zero(),misbound).count());
		Index<ABlob,SignedData<ACell>> deposited=entries(e);
		assertSame(deposited,BOB_MAILBOX.merge(deposited,misbound));
	}

	@Test
	public void testRejectsEnvelopeAddressedElsewhere() {
		MailEnvelope toAlice=envelope(BOB,BOB_DID,ALICE_DID,ALICE,1000,"for alice");
		Index<ABlob,SignedData<ACell>> misplaced=entries(toAlice);
		assertEquals(0,BOB_MAILBOX.merge(BOB_MAILBOX.zero(),misplaced).count());
	}

	@Test
	public void testRejectsBadSignature() {
		MailEnvelope e=toBob(1000,"hello");
		SignedData<ACell> tampered=SignedData.create(ALICE.getAccountKey(),Ed25519Signature.ZERO,e.signed().getValueRef());
		Index<ABlob,SignedData<ACell>> bad=Index.create(e.key(),tampered);
		assertEquals(0,BOB_MAILBOX.merge(BOB_MAILBOX.zero(),bad).count());

		MailReceipt r=receipt(BOB,BOB_DID,e);
		SignedData<ACell> tamperedReceipt=SignedData.create(BOB.getAccountKey(),Ed25519Signature.ZERO,r.signed().getValueRef());
		Index<ABlob,SignedData<ACell>> badReceipt=Index.create(r.key(),tamperedReceipt);
		Index<ABlob,SignedData<ACell>> deposited=entries(e);
		assertSame(deposited,BOB_MAILBOX.merge(deposited,badReceipt));
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

		assertEquals(0,BOB_MAILBOX.merge(BOB_MAILBOX.zero(),misfiled).count());
		assertSame(deposited,BOB_MAILBOX.merge(deposited,misfiled));
		assertEquals(deposited,BOB_MAILBOX.merge(misfiled,deposited));

		// Under its own key the same receipt replaces its own envelope and nothing else
		Index<ABlob,SignedData<ACell>> both=entries(e1,e2);
		Index<ABlob,SignedData<ACell>> merged=BOB_MAILBOX.merge(both,entries(r1));
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

		assertEquals(0,BOB_MAILBOX.merge(BOB_MAILBOX.zero(),misfiled).count());
		assertSame(honest,BOB_MAILBOX.merge(honest,misfiled));
		assertEquals(honest,BOB_MAILBOX.merge(misfiled,honest));

		// A key of the wrong shape has no register at all
		Index<ABlob,SignedData<ACell>> badKey=Index.create(Blob.fromHex("ff"),e1.signed());
		assertEquals(0,BOB_MAILBOX.merge(BOB_MAILBOX.zero(),badKey).count());
	}

	@Test
	public void testPathBindsKey() {
		MailEnvelope e1=toBob(1000,"one");
		MailEnvelope e2=toBob(1001,"two");
		ALattice<SignedData<ACell>> at1=BOB_MAILBOX.path(e1.key());
		assertNotNull(at1);
		assertEquals(e1.key(),((MailEntryLattice)at1).getKey());
		assertEquals(BOB_DID,((MailEntryLattice)at1).getDID());
		assertNull(BOB_MAILBOX.path(Blob.fromHex("ff")));
		assertNull(BOB_MAILBOX.path(BOB_DID));

		// The register at one key accepts only entries that belong there
		assertEquals(e1.signed(),at1.merge(null,e1.signed()));
		assertNull(at1.merge(null,e2.signed()));
		assertNull(at1.merge(null,receipt(BOB,BOB_DID,e2).signed()));
		assertEquals(e1.key(),BOB_MAILBOX.resolveKey(Strings.create(e1.key().toHexString())));
		assertNull(BOB_MAILBOX.resolveKey(Strings.create("ff")));
		assertThrows(IllegalArgumentException.class,
			() -> MailEntryLattice.create(BOB_DID,Blob.fromHex("ff"),DIDKeyAuthorizer.CONVEX));
	}

	@Test
	public void testOversizedBodyIsDropped() {
		Blob big=Blob.createRandom(new Random(7),MailEnvelope.MAX_BODY_LENGTH+1);
		assertThrows(IllegalArgumentException.class,() -> MailEnvelope.plain(ALICE,BOB_DID,ALICE_DID,1000,big));

		// A hand-built oversized envelope is dropped by the merge before any verification
		SignedData<ACell> oversized=ALICE.signData(Vectors.of(MailEnvelope.CONTEXT,BOB_DID,ALICE_DID,
			CVMLong.create(1000),CVMBool.FALSE,big));
		ABlob key=MailEntryLattice.key(1000,oversized.getHash());
		assertEquals(0,BOB_MAILBOX.merge(BOB_MAILBOX.zero(),Index.create(key,oversized)).count());
	}

	@Test
	public void testRegionDropsNonDidKeys() {
		MailEnvelope e=toBob(1000,"hello");
		AHashMap<ACell,Index<ABlob,SignedData<ACell>>> junk=Maps.of(
			Strings.create("not a did"),entries(e),
			Strings.create("did:key"),entries(e));
		assertEquals(0,Mail.LATTICE.merge(Mail.LATTICE.zero(),junk).count());
		assertNull(Mail.LATTICE.resolveKey(Strings.create("not a did")));
		assertEquals(BOB_DID,Mail.LATTICE.resolveKey(BOB_DID));
		ALattice<ACell> child=Mail.LATTICE.path(BOB_DID);
		assertNotNull(child);
		assertNull(Mail.LATTICE.path(Strings.create("nope")));
	}
}
