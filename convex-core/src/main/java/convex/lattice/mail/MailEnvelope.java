package convex.lattice.mail;

import convex.auth.did.DID;
import convex.core.crypto.AKeyPair;
import convex.core.crypto.ECIES;
import convex.core.cvm.CVMEncoder;
import convex.core.data.ABlob;
import convex.core.data.ACell;
import convex.core.data.AHashMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.AccountKey;
import convex.core.data.Blob;
import convex.core.data.Format;
import convex.core.data.Hash;
import convex.core.data.Keyword;
import convex.core.data.Maps;
import convex.core.data.SignedData;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMBool;
import convex.core.data.prim.CVMLong;
import convex.core.exceptions.BadFormatException;
import convex.core.util.Utils;

/**
 * A deposited mail envelope: a {@link SignedData} over the vector
 * {@code ["convex-mail-v1" to from issued-at encrypted body]}, signed by any key.
 *
 * <p>{@code to} is the recipient's canonical base DID and sits inside the signature, so
 * an envelope cannot be moved into another mailbox. {@code from} is an optional sender
 * DID; the recipient checks the signer is authorised for it. An encrypted body is the
 * {@link ECIES} ciphertext of the CAD3 multi-cell encoding of a payload map which
 * repeats {@code :to}, {@code :from} and {@code :issued-at}, because the cipher binds
 * no associated data (CAD052); {@link #open} rejects a payload that disagrees with its
 * envelope.</p>
 */
public final class MailEnvelope {

	public static final AString CONTEXT=Strings.create("convex-mail-v1");

	public static final Keyword TO=Keyword.intern("to");
	public static final Keyword FROM=Keyword.intern("from");
	public static final Keyword ISSUED_AT=Keyword.intern("issued-at");
	public static final Keyword ID=Keyword.intern("id");
	public static final Keyword TYPE=Keyword.intern("type");
	public static final Keyword CONTENT=Keyword.intern("content");

	/** Largest body accepted, in bytes: a merge drops anything larger before verifying it. */
	public static final long MAX_BODY_LENGTH=64*1024;

	private static final int FIELDS=6;

	private final SignedData<ACell> signed;
	private final AString to;
	private final AString from;
	private final long issuedAt;
	private final boolean encrypted;
	private final ACell body;

	private MailEnvelope(SignedData<ACell> signed, AString to, AString from, long issuedAt,
			boolean encrypted, ACell body) {
		this.signed=signed;
		this.to=to;
		this.from=from;
		this.issuedAt=issuedAt;
		this.encrypted=encrypted;
		this.body=body;
	}

	/**
	 * Parses a stored entry as an envelope. Checks structure only; the signature is
	 * verified separately through {@link #signed()}.
	 *
	 * @param value Candidate entry value
	 * @return Envelope, or null if the value is not a well-formed envelope
	 */
	@SuppressWarnings("unchecked")
	public static MailEnvelope parse(ACell value) {
		if (!(value instanceof SignedData<?> signed)) return null;
		if (!(signed.getValue() instanceof AVector<?> v) || v.count()!=FIELDS) return null;
		if (!CONTEXT.equals(v.get(0))) return null;
		if (!(v.get(1) instanceof AString to) || !DID.isCanonicalBase(to)) return null;
		ACell fromCell=v.get(2);
		AString from=null;
		if (fromCell!=null) {
			if (!(fromCell instanceof AString f) || !DID.isCanonicalBase(f)) return null;
			from=f;
		}
		if (!(v.get(3) instanceof CVMLong t)) return null;
		if (!(v.get(4) instanceof CVMBool enc)) return null;
		ACell body=v.get(5);
		if (enc.booleanValue() && (!(body instanceof ABlob b) || b.count()<ECIES.OVERHEAD)) return null;
		long size=(body instanceof ABlob b)?b.count():((body==null)?0:body.getEncodingLength());
		if (size>MAX_BODY_LENGTH) return null;
		return new MailEnvelope((SignedData<ACell>)signed,to,from,t.longValue(),enc.booleanValue(),body);
	}

	/**
	 * Creates and signs an envelope with an encrypted body.
	 *
	 * @param signer Envelope author
	 * @param to Recipient canonical base DID
	 * @param recipientKey Key to encrypt to
	 * @param from Sender DID, or null for an unattributed envelope
	 * @param issuedAt Sender timestamp
	 * @param payload Payload map; {@code :to}, {@code :from} and {@code :issued-at} are set here
	 * @return Signed envelope
	 */
	public static MailEnvelope seal(AKeyPair signer, AString to, AccountKey recipientKey, AString from,
			long issuedAt, AHashMap<Keyword,ACell> payload) {
		AHashMap<Keyword,ACell> bound=(payload==null)?Maps.empty():payload;
		bound=bound.assoc(TO,to).assoc(FROM,from).assoc(ISSUED_AT,CVMLong.create(issuedAt));
		Blob plaintext=Format.encodeMultiCell(bound,true);
		return create(signer,to,from,issuedAt,true,ECIES.encrypt(recipientKey,plaintext));
	}

	/** Creates and signs an envelope with a plain body. */
	public static MailEnvelope plain(AKeyPair signer, AString to, AString from, long issuedAt, ACell body) {
		return create(signer,to,from,issuedAt,false,body);
	}

	/**
	 * Creates and signs an envelope around a body supplied as it is: ciphertext the
	 * caller produced, or a plain cell.
	 *
	 * @param signer Envelope author
	 * @param to Recipient canonical base DID
	 * @param from Sender DID, or null
	 * @param issuedAt Sender timestamp
	 * @param encrypted Whether {@code body} is an ECIES ciphertext
	 * @param body Body
	 * @return Signed envelope
	 */
	public static MailEnvelope create(AKeyPair signer, AString to, AString from, long issuedAt,
			boolean encrypted, ACell body) {
		if (signer==null) throw new IllegalArgumentException("Signer must not be null");
		AVector<ACell> v=Vectors.of(CONTEXT,to,from,CVMLong.create(issuedAt),CVMBool.create(encrypted),body);
		MailEnvelope envelope=parse(signer.signData(v));
		if (envelope==null) throw new IllegalArgumentException("Invalid envelope fields");
		return envelope;
	}

	/**
	 * Opens the body. An encrypted body is decrypted, decoded and checked against the
	 * envelope header; a plain body is returned as it is.
	 *
	 * @param decryptor Recipient decryptor, ignored for a plain body
	 * @return Payload
	 * @throws BadFormatException If decryption fails or the payload disagrees with its envelope
	 */
	@SuppressWarnings("unchecked")
	public ACell open(ECIES.Decryptor decryptor) throws BadFormatException {
		if (!encrypted) return body;
		if (decryptor==null) throw new BadFormatException("Encrypted body needs a decryptor");
		Blob plaintext=decryptor.decrypt(((ABlob)body).toFlatBlob());
		ACell payload=CVMEncoder.INSTANCE.decodeMultiCell(plaintext);
		if (!(payload instanceof AHashMap<?,?> raw)) throw new BadFormatException("Mail payload must be a map");
		AHashMap<Keyword,ACell> map=(AHashMap<Keyword,ACell>)raw;
		if (!Utils.equals(map.get(TO),to) || !Utils.equals(map.get(FROM),from)
				|| !Utils.equals(map.get(ISSUED_AT),CVMLong.create(issuedAt))) {
			throw new BadFormatException("Mail payload does not match its envelope");
		}
		return map;
	}

	/** The signed entry as stored in a mailbox. */
	public SignedData<ACell> signed() { return signed; }

	/** Hash of the signed envelope: the identity of the message. */
	public Hash hash() { return signed.getHash(); }

	/** The mailbox entry key, {@code issued-at ‖ hash}. */
	public ABlob key() { return MailEntryLattice.key(issuedAt,hash()); }

	public AccountKey signer() { return signed.getAccountKey(); }
	public AString to() { return to; }
	public AString from() { return from; }
	public long issuedAt() { return issuedAt; }
	public boolean encrypted() { return encrypted; }
	public ACell body() { return body; }

	@Override
	public boolean equals(Object o) {
		return (o instanceof MailEnvelope e) && signed.equals(e.signed);
	}

	@Override
	public int hashCode() { return hash().hashCode(); }

	@Override
	public String toString() { return "MailEnvelope "+hash()+" to "+to; }
}
