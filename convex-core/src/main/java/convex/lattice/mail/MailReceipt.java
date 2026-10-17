package convex.lattice.mail;

import convex.auth.did.DID;
import convex.core.crypto.AKeyPair;
import convex.core.data.ABlob;
import convex.core.data.ACell;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Hash;
import convex.core.data.SignedData;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;
import convex.lattice.LatticeContext;

/**
 * A recipient's receipt for one envelope: a {@link SignedData} over the vector
 * {@code ["convex-mail-receipt-v1" did hash issued-at]}, signed by a key authorised for
 * the mailbox DID.
 *
 * <p>{@code hash} and {@code issued-at} name the envelope and reproduce its entry key,
 * so the receipt lands on exactly the entry it confirms and replaces it there. A receipt
 * is a complete entry on its own: arriving before its envelope, it is a tombstone that
 * absorbs the envelope whenever it turns up.</p>
 */
public final class MailReceipt {

	public static final AString CONTEXT=Strings.create("convex-mail-receipt-v1");

	private static final int FIELDS=4;

	private final SignedData<ACell> signed;
	private final AString did;
	private final Hash hash;
	private final long issuedAt;

	private MailReceipt(SignedData<ACell> signed, AString did, Hash hash, long issuedAt) {
		this.signed=signed;
		this.did=did;
		this.hash=hash;
		this.issuedAt=issuedAt;
	}

	/**
	 * Parses a stored entry as a receipt. Checks structure only; signature and signer
	 * authorisation are the entry lattice's job.
	 *
	 * @param value Candidate entry value
	 * @return Receipt, or null if the value is not a well-formed receipt
	 */
	@SuppressWarnings("unchecked")
	public static MailReceipt parse(ACell value) {
		if (!(value instanceof SignedData<?> signed)) return null;
		if (!(signed.getValue() instanceof AVector<?> v) || v.count()!=FIELDS) return null;
		if (!CONTEXT.equals(v.get(0))) return null;
		if (!(v.get(1) instanceof AString did) || !DID.isCanonicalBase(did)) return null;
		if (!(v.get(2) instanceof ABlob h)) return null;
		Hash hash=Hash.wrap(h);
		if (hash==null) return null;
		if (!(v.get(3) instanceof CVMLong t)) return null;
		return new MailReceipt((SignedData<ACell>)signed,did,hash,t.longValue());
	}

	/**
	 * Authors a receipt on behalf of the mailbox owner.
	 *
	 * @param context Policy holding a signer authorised for {@code did}
	 * @param did Mailbox DID
	 * @param envelope Envelope being received
	 * @return Signed receipt, or null when the context has no authorised signer
	 */
	public static MailReceipt create(LatticeContext context, AString did, MailEnvelope envelope) {
		AVector<ACell> v=vector(did,envelope.hash(),envelope.issuedAt());
		SignedData<AVector<ACell>> signed=context.signAs(did,v);
		return (signed==null)?null:parse(signed);
	}

	/** Signs a receipt with an explicit key pair; the caller vouches for its authority. */
	public static MailReceipt sign(AKeyPair signer, AString did, Hash hash, long issuedAt) {
		return parse(signer.signData(vector(did,hash,issuedAt)));
	}

	private static AVector<ACell> vector(AString did, Hash hash, long issuedAt) {
		return Vectors.of(CONTEXT,did,hash,CVMLong.create(issuedAt));
	}

	/** The signed entry as stored in a mailbox. */
	public SignedData<ACell> signed() { return signed; }

	/** The entry key this receipt occupies, {@code issued-at ‖ hash}. */
	public ABlob key() { return MailEntryLattice.key(issuedAt,hash); }

	public AString did() { return did; }
	public Hash hash() { return hash; }
	public long issuedAt() { return issuedAt; }

	@Override
	public String toString() { return "MailReceipt for "+hash+" in "+did; }
}
