package convex.lattice.mail;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.function.BiFunction;

import convex.auth.did.DID;
import convex.auth.did.DIDKeyAuthorizer;
import convex.core.crypto.ECIES;
import convex.core.data.ABlob;
import convex.core.data.ACell;
import convex.core.data.AString;
import convex.core.data.Blob;
import convex.core.data.Hash;
import convex.core.data.Index;
import convex.core.data.SignedData;
import convex.core.exceptions.BadFormatException;
import convex.core.util.MergeFunction;
import convex.core.util.Utils;
import convex.lattice.ALatticeComponent;
import convex.lattice.TransactionalComponent;
import convex.lattice.cursor.AForkableCursor;

/**
 * Component at one mailbox, {@code [:mail did]}: an index of entries keyed by
 * {@code issued-at ‖ hash}, each a two-state register.
 *
 * <p>An entry is a deposited {@link MailEnvelope}, replaced by the recipient's
 * {@link MailReceipt}. Merge keeps, per entry key, a valid receipt over a valid
 * envelope, and of two valid receipts the one whose signed value has the lower hash.
 * An envelope is valid at its key when its signature verifies, it is addressed to
 * this mailbox and the key is its own; a receipt when, in addition, it names this
 * mailbox and its signer is authorised for the mailbox DID. Only received entries
 * are checked: a held entry was checked when it was admitted, so a receipt stays
 * held even if its signer is later no longer authorised. Each received entry is
 * judged alone: one that is invalid, is not signed data or sits under a key of the
 * wrong shape is dropped, and the rest merge. A received mailbox that is not an index
 * is dropped.</p>
 *
 * <p>Anyone holding this view may {@link #deposit}. Opening and receiving are the
 * recipient's: {@link #open} needs the decryptors for the recipient's keys and
 * {@link #receive} needs an explicit signer authorised for the DID. Every
 * write merges by the same rule, so a receipted entry absorbs a later deposit.
 * Attempted local authoring with an unavailable or unauthorised signer fails before
 * writing the receipt.</p>
 */
public final class Mailbox extends ALatticeComponent<Index<ABlob,SignedData<ACell>>>
		implements TransactionalComponent<Index<ABlob,SignedData<ACell>>,Mailbox> {

	/** Entry key length: 8-byte big-endian issued-at followed by the 32-byte envelope hash. */
	public static final int KEY_LENGTH=8+Hash.LENGTH;

	private final AString did;
	private final DIDKeyAuthorizer authorizer;
	private final BiFunction<ACell,ACell,SignedData<ACell>> signer;

	Mailbox(AForkableCursor<Index<ABlob,SignedData<ACell>>> cursor, AString did, DIDKeyAuthorizer authorizer,
			BiFunction<ACell,ACell,SignedData<ACell>> signer) {
		super(cursor);
		if (!DID.isCanonicalBase(did)) throw new IllegalArgumentException("Mailbox must be a canonical base DID");
		this.did=did;
		this.authorizer=Objects.requireNonNull(authorizer,"Authorizer must not be null");
		this.signer=signer;
	}

	/** Builds the entry key for an envelope: {@code issued-at ‖ hash}. */
	public static ABlob key(long issuedAt, Hash hash) {
		byte[] bs=new byte[KEY_LENGTH];
		Utils.writeLong(bs,0,issuedAt);
		hash.getBytes(bs,8);
		return Blob.wrap(bs);
	}

	/** The mailbox DID. */
	public AString did() { return did; }

	/** Envelopes not yet receipted, in key order (issued-at, then hash). */
	public List<MailEnvelope> deposited() {
		List<MailEnvelope> list=new ArrayList<>();
		Index<ABlob,SignedData<ACell>> index=cursor().getOrDefault(null);
		if (index!=null) index.forEach((key,value) -> {
			MailEnvelope envelope=MailEnvelope.parse(value);
			if (envelope!=null) list.add(envelope);
		});
		return list;
	}

	/** Receipts held, in key order. */
	public List<MailReceipt> receipts() {
		List<MailReceipt> list=new ArrayList<>();
		Index<ABlob,SignedData<ACell>> index=cursor().getOrDefault(null);
		if (index!=null) index.forEach((key,value) -> {
			MailReceipt receipt=MailReceipt.parse(value);
			if (receipt!=null) list.add(receipt);
		});
		return list;
	}

	/** True iff the entry for this envelope is a receipt. */
	public boolean isReceived(MailEnvelope envelope) {
		return isReceived(envelope.key());
	}

	/** True iff the entry under this key is a receipt. */
	public boolean isReceived(ABlob key) {
		Index<ABlob,SignedData<ACell>> index=cursor().getOrDefault(null);
		return index!=null && MailReceipt.parse(index.get(key))!=null;
	}

	/**
	 * Deposits an envelope addressed to this mailbox.
	 *
	 * @param envelope Envelope whose {@code to} is this mailbox's DID
	 */
	public void deposit(MailEnvelope envelope) {
		if (!did.equals(envelope.to())) {
			throw new IllegalArgumentException("Envelope is addressed to "+envelope.to()+", not "+did);
		}
		merge(Index.create(envelope.key(),envelope.signed()));
	}

	/**
	 * Receipts a batch atomically on behalf of the mailbox owner. Each receipt
	 * replaces its envelope in the mailbox. Signing and verification complete before
	 * the transaction begins, so retries reuse the prepared receipts. Failure leaves
	 * the mailbox unchanged; on a fork or enclosing transaction, changes remain there.
	 *
	 * @param envelopes Envelopes the recipient has taken
	 * @return The receipts written
	 * @throws IllegalStateException If the signer cannot author a valid receipt for this mailbox
	 */
	public List<MailReceipt> receive(Collection<MailEnvelope> envelopes) {
		List<MailReceipt> receipts=new ArrayList<>();
		for (MailEnvelope envelope:envelopes) {
			MailReceipt receipt=MailReceipt.create(signer,did,envelope);
			if (receipt==null || receipt(did,envelope.key(),authorizer,receipt.signed())==null) {
				throw new IllegalStateException("No signer authorised for mailbox "+did);
			}
			receipts.add(receipt);
		}
		execute(draft -> {
			for (MailReceipt receipt:receipts) draft.merge(Index.create(receipt.key(),receipt.signed()));
		});
		return receipts;
	}

	/** Receipts the given envelopes. */
	public List<MailReceipt> receive(MailEnvelope... envelopes) {
		return receive(List.of(envelopes));
	}

	/**
	 * Opens every deposited envelope this recipient can read. Encrypted bodies are tried
	 * against each decryptor; an envelope that none opens, or whose payload disagrees
	 * with its header, is skipped.
	 *
	 * <p>The sender claim is checked against the mailbox authoriser and reported, not
	 * enforced: a message whose {@code from} the signer is not authorised for is
	 * returned with {@link MailMessage#fromAuthorised()} false, because the envelope is
	 * still a readable deposit. Anyone holding a ciphertext can re-wrap it under a
	 * claimed sender, so a caller that acts on {@code from} must check that flag.</p>
	 *
	 * @param decryptors Decryptors for the recipient's keys
	 * @return Opened messages in key order
	 */
	public List<MailMessage> open(ECIES.Decryptor... decryptors) {
		List<MailMessage> messages=new ArrayList<>();
		for (MailEnvelope envelope:deposited()) {
			ACell payload=null;
			boolean opened=false;
			if (envelope.encrypted()) {
				for (ECIES.Decryptor decryptor:decryptors) {
					try {
						payload=envelope.open(decryptor);
						opened=true;
						break;
					} catch (BadFormatException e) {
						// not for this key, or a payload that disagrees with its envelope
					}
				}
			} else {
				payload=envelope.body();
				opened=true;
			}
			if (!opened) continue;
			boolean fromAuthorised=envelope.from()!=null
				&& authorizer.safeAuthorises(envelope.from(),envelope.signer());
			messages.add(new MailMessage(envelope.hash(),envelope.to(),envelope.from(),envelope.signer(),
				fromAuthorised,envelope.issuedAt(),payload,envelope.encrypted()));
		}
		return messages;
	}

	/**
	 * Merges an incoming mailbox value into this mailbox, entry by entry, by the
	 * register rule. A value that is not an index changes nothing.
	 *
	 * @param incoming Incoming mailbox value, of any type
	 * @return Current working value
	 */
	@Override
	public Index<ABlob,SignedData<ACell>> merge(ACell incoming) {
		if (incoming!=null && !(incoming instanceof Index)) return cursor().getOrDefault(null);
		return cursor().updateAndGet(held -> mergeEntries(did,authorizer,held,incoming));
	}

	/**
	 * Merges a delivery of one entry at its key; any other path names nothing here and
	 * is dropped, as is an entry that is not signed data.
	 */
	@SuppressWarnings("unchecked")
	@Override
	protected Index<ABlob,SignedData<ACell>> mergeAtPath(ACell value,ACell... path) {
		if (path.length==1 && isKey(path[0]) && value instanceof SignedData<?> entry) {
			return merge(Index.create((ABlob)path[0],(SignedData<ACell>)entry));
		}
		return cursor().getOrDefault(null);
	}

	/** Creates an isolated working copy, merged back by the register rule on sync. */
	public Mailbox fork() {
		return rebind(storage.fork());
	}

	@Override
	public Mailbox rebind(AForkableCursor<Index<ABlob,SignedData<ACell>>> cursor) {
		return new Mailbox(cursor,did,authorizer,signer);
	}

	/** Reconciles this component's draft by the register alone: both sides are held values. */
	@Override
	protected Index<ABlob,SignedData<ACell>> reconcile(
			Index<ABlob,SignedData<ACell>> own,
			Index<ABlob,SignedData<ACell>> other) {
		return mergeHeld(own,other);
	}

	/**
	 * Merges an incoming mailbox value into a held one under a mailbox DID and receipt
	 * signer rule. Only the incoming entries are checked, and only entries that differ
	 * are visited. An absent held value merges as an empty mailbox, and a held value
	 * that takes nothing is returned itself. An incoming value that is not an index is
	 * dropped.
	 */
	@SuppressWarnings({"unchecked","rawtypes"})
	static Index<ABlob,SignedData<ACell>> mergeEntries(AString did, DIDKeyAuthorizer authorizer,
			Index<ABlob,SignedData<ACell>> own, ACell incoming) {
		if (!(incoming instanceof Index<?,?> other)) return own;
		// An absent mailbox is merged against an empty one, so every incoming entry meets the rule
		Index<ABlob,SignedData<ACell>> held=(own==null)?Index.none():own;
		return ((Index)held).mergeDifferences(other,new MergeFunction<ACell>() {
			@Override
			public ACell merge(ACell a,ACell b) {
				throw new IllegalStateException("Mailbox merge requires the entry key");
			}

			@Override
			public ACell merge(Object key,ACell a,ACell b) {
				if (b==null || !isKey(key) || !(b instanceof SignedData<?> entry)) return a;
				return mergeEntry(did,(ABlob)key,authorizer,(SignedData<ACell>)a,(SignedData<ACell>)entry);
			}
		});
	}

	/**
	 * Merges two held mailbox values entry by entry by the register alone. Only entries
	 * that differ are visited, and a value that takes nothing is returned itself.
	 */
	static Index<ABlob,SignedData<ACell>> mergeHeld(Index<ABlob,SignedData<ACell>> own,
			Index<ABlob,SignedData<ACell>> other) {
		if (other==null) return own;
		if (own==null) return other;
		return own.mergeDifferences(other,Mailbox::register);
	}

	/** True iff a key has the shape of an entry key. */
	private static boolean isKey(Object key) {
		return (key instanceof ABlob b) && b.count()==KEY_LENGTH;
	}

	/** The register at one entry, taking a received value only if it is valid there. */
	private static SignedData<ACell> mergeEntry(AString did, ABlob key, DIDKeyAuthorizer authorizer,
			SignedData<ACell> held, SignedData<ACell> received) {
		// The held value was checked when it was admitted, so only the received one is
		if (receipt(did,key,authorizer,received)==null && envelope(did,key,received)==null) return held;
		return register(held,received);
	}

	/**
	 * The register between two admitted values, either of which may be absent: a
	 * receipt beats an envelope, and of two of a kind the lower hash is kept.
	 */
	private static SignedData<ACell> register(SignedData<ACell> a, SignedData<ACell> b) {
		if (a==null) return b;
		if (b==null) return a;
		boolean aReceipt=MailReceipt.parse(a)!=null;
		if (aReceipt!=(MailReceipt.parse(b)!=null)) return aReceipt?a:b;
		// Two admitted envelopes under one key are one envelope, since the key holds its
		// hash; the tiebreak only keeps the rule total
		return lowerHash(a,b);
	}

	/**
	 * Returns the value if it is a receipt for this entry by an authorised signer, else
	 * null. The cheap structural checks run before the signature is verified.
	 */
	private static SignedData<ACell> receipt(AString did, ABlob key, DIDKeyAuthorizer authorizer, SignedData<ACell> value) {
		if (value==null) return null;
		MailReceipt r=MailReceipt.parse(value);
		if (r==null || !did.equals(r.did()) || !key.equals(r.key())) return null;
		if (!value.checkSignature() || !authorizer.safeAuthorises(did,value.getAccountKey())) return null;
		return value;
	}

	/** Returns the value if it is a validly signed envelope belonging at this entry, else null. */
	private static SignedData<ACell> envelope(AString did, ABlob key, SignedData<ACell> value) {
		if (value==null) return null;
		MailEnvelope e=MailEnvelope.parse(value);
		if (e==null || !did.equals(e.to()) || !key.equals(e.key())) return null;
		return value.checkSignature()?value:null;
	}

	private static SignedData<ACell> lowerHash(SignedData<ACell> a, SignedData<ACell> b) {
		return (a.getHash().compareTo(b.getHash())<=0)?a:b;
	}
}
