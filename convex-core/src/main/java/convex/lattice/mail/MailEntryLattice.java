package convex.lattice.mail;

import convex.auth.did.DID;
import convex.auth.did.DIDKeyAuthorizer;
import convex.core.data.ABlob;
import convex.core.data.ACell;
import convex.core.data.AString;
import convex.core.data.Blob;
import convex.core.data.Hash;
import convex.core.data.SignedData;
import convex.core.util.Utils;
import convex.lattice.ALattice;
import convex.lattice.generic.AValueLattice;

/**
 * The two-state register at one mailbox entry: a deposited {@link MailEnvelope}, replaced
 * by the recipient's {@link MailReceipt}. Bound to a mailbox DID, whose authoriser
 * decides which signers may issue receipts, and to the entry key {@code issued-at ‖ hash},
 * which a valid value must reproduce from its own fields.
 *
 * <p>Join: any valid receipt beats any envelope; two receipts resolve to the lower hash
 * of the signed value (the CAD045 canonical tiebreak, since both are the owner's and
 * only the state matters); two envelopes under one key are the same envelope. Invalid
 * values are dropped on both sides, so merging against an absent own value never admits
 * an unverified entry.</p>
 *
 * <p>The key binding is what makes the register safe in an open index. A receipt names
 * the envelope it confirms, so it is valid only under that envelope's key and can never
 * replace another message; a copy of an envelope filed under any other key is dropped
 * rather than competing with the entry that belongs there.</p>
 *
 * <p>This is the only merge rule the mail region adds. The mailbox is a
 * {@link MailboxLattice} of these registers and the region a map of mailboxes.</p>
 */
public final class MailEntryLattice extends AValueLattice<SignedData<ACell>> {

	/** Entry key length: 8-byte big-endian issued-at followed by the 32-byte envelope hash. */
	public static final int KEY_LENGTH=8+Hash.LENGTH;

	private final AString did;
	private final ABlob key;
	private final DIDKeyAuthorizer authorizer;

	private MailEntryLattice(AString did, ABlob key, DIDKeyAuthorizer authorizer) {
		this.did=did;
		this.key=key;
		this.authorizer=authorizer;
	}

	/**
	 * Creates the register for one entry of one mailbox.
	 *
	 * @param did Mailbox canonical base DID
	 * @param key Entry key, {@code issued-at ‖ hash}
	 * @param authorizer Rule deciding which keys may sign receipts for the DID
	 * @return Entry lattice bound to the mailbox and the key
	 */
	public static MailEntryLattice create(AString did, ABlob key, DIDKeyAuthorizer authorizer) {
		if (!DID.isCanonicalBase(did)) throw new IllegalArgumentException("Mailbox must be a canonical base DID");
		if (!isKey(key)) throw new IllegalArgumentException("Entry key must be issued-at followed by hash");
		if (authorizer==null) throw new IllegalArgumentException("Authorizer must not be null");
		return new MailEntryLattice(did,key,authorizer);
	}

	/** Builds the entry key for an envelope: {@code issued-at ‖ hash}. */
	public static ABlob key(long issuedAt, Hash hash) {
		byte[] bs=new byte[KEY_LENGTH];
		Utils.writeLong(bs,0,issuedAt);
		hash.getBytes(bs,8);
		return Blob.wrap(bs);
	}

	/** True iff a cell has the shape of an entry key. */
	public static boolean isKey(ACell key) {
		return (key instanceof ABlob b) && b.count()==KEY_LENGTH;
	}

	public AString getDID() { return did; }
	public ABlob getKey() { return key; }
	public DIDKeyAuthorizer getAuthorizer() { return authorizer; }

	@Override
	public SignedData<ACell> merge(SignedData<ACell> own, SignedData<ACell> other) {
		SignedData<ACell> ownReceipt=receipt(own);
		SignedData<ACell> otherReceipt=receipt(other);
		if (ownReceipt!=null && otherReceipt!=null) return lowerHash(ownReceipt,otherReceipt);
		if (ownReceipt!=null) return ownReceipt;
		if (otherReceipt!=null) return otherReceipt;
		SignedData<ACell> ownEnvelope=envelope(own);
		SignedData<ACell> otherEnvelope=envelope(other);
		// Two valid envelopes under one key are one envelope, since the key holds its
		// hash; the tiebreak only keeps the rule total
		if (ownEnvelope!=null && otherEnvelope!=null) return lowerHash(ownEnvelope,otherEnvelope);
		return (ownEnvelope!=null)?ownEnvelope:otherEnvelope;
	}

	/**
	 * Returns the value if it is a receipt for this entry by an authorised signer, else
	 * null. The cheap structural checks run before the signature is verified.
	 */
	public SignedData<ACell> receipt(SignedData<ACell> value) {
		if (value==null) return null;
		MailReceipt r=MailReceipt.parse(value);
		if (r==null || !did.equals(r.did()) || !key.equals(r.key())) return null;
		if (!value.checkSignature() || !authorizer.safeAuthorises(did,value.getAccountKey())) return null;
		return value;
	}

	/** Returns the value if it is a validly signed envelope belonging at this entry, else null. */
	public SignedData<ACell> envelope(SignedData<ACell> value) {
		if (value==null) return null;
		MailEnvelope e=MailEnvelope.parse(value);
		if (e==null || !did.equals(e.to()) || !key.equals(e.key())) return null;
		return value.checkSignature()?value:null;
	}

	private static SignedData<ACell> lowerHash(SignedData<ACell> a, SignedData<ACell> b) {
		return (a.getHash().compareTo(b.getHash())<=0)?a:b;
	}

	@Override
	public SignedData<ACell> zero() {
		return null;
	}

	@Override
	public boolean checkForeign(SignedData<ACell> value) {
		return value instanceof SignedData;
	}

	@Override
	public <T extends ACell> ALattice<T> path(ACell childKey) {
		return null;
	}
}
