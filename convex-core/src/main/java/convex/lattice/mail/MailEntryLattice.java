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
 * decides which signers may issue receipts.
 *
 * <p>Join: any valid receipt beats any envelope; two receipts resolve to the lower hash
 * of the signed value (the CAD045 canonical tiebreak, since both are the owner's and
 * only the state matters); two envelopes under one key are the same envelope. Invalid
 * values are dropped on both sides, so merging against an absent own value never admits
 * an unverified entry.</p>
 *
 * <p>This is the only merge rule the mail region adds. The mailbox is an
 * {@code IndexLattice} of these registers and the region a map of mailboxes.</p>
 */
public final class MailEntryLattice extends AValueLattice<SignedData<ACell>> {

	/** Entry key length: 8-byte big-endian issued-at followed by the 32-byte envelope hash. */
	public static final int KEY_LENGTH=8+Hash.LENGTH;

	private final AString did;
	private final DIDKeyAuthorizer authorizer;

	private MailEntryLattice(AString did, DIDKeyAuthorizer authorizer) {
		this.did=did;
		this.authorizer=authorizer;
	}

	/**
	 * Creates the register for one mailbox.
	 *
	 * @param did Mailbox canonical base DID
	 * @param authorizer Rule deciding which keys may sign receipts for the DID
	 * @return Entry lattice bound to the mailbox
	 */
	public static MailEntryLattice create(AString did, DIDKeyAuthorizer authorizer) {
		if (!DID.isCanonicalBase(did)) throw new IllegalArgumentException("Mailbox must be a canonical base DID");
		if (authorizer==null) throw new IllegalArgumentException("Authorizer must not be null");
		return new MailEntryLattice(did,authorizer);
	}

	/** Builds the entry key for an envelope: {@code issued-at ‖ hash}. */
	public static ABlob key(long issuedAt, Hash hash) {
		byte[] bs=new byte[KEY_LENGTH];
		Utils.writeLong(bs,0,issuedAt);
		hash.getBytes(bs,8);
		return Blob.wrap(bs);
	}

	public AString getDID() { return did; }
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
		// Two envelopes under one key are the same envelope when the key is honest; a
		// dishonest key still converges, by the same canonical tiebreak as receipts
		if (ownEnvelope!=null && otherEnvelope!=null) return lowerHash(ownEnvelope,otherEnvelope);
		return (ownEnvelope!=null)?ownEnvelope:otherEnvelope;
	}

	/** Returns the value if it is a receipt for this mailbox by an authorised signer, else null. */
	public SignedData<ACell> receipt(SignedData<ACell> value) {
		if (value==null) return null;
		MailReceipt r=MailReceipt.parse(value);
		if (r==null || !did.equals(r.did())) return null;
		if (!value.checkSignature() || !authorizer.safeAuthorises(did,value.getAccountKey())) return null;
		return value;
	}

	/** Returns the value if it is a validly signed envelope addressed to this mailbox, else null. */
	public SignedData<ACell> envelope(SignedData<ACell> value) {
		if (value==null) return null;
		MailEnvelope e=MailEnvelope.parse(value);
		if (e==null || !did.equals(e.to())) return null;
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
