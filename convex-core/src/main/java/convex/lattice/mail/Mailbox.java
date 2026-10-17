package convex.lattice.mail;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import convex.core.crypto.ECIES;
import convex.core.data.ABlob;
import convex.core.data.ACell;
import convex.core.data.AString;
import convex.core.data.Index;
import convex.core.data.SignedData;
import convex.core.exceptions.BadFormatException;
import convex.lattice.ALatticeComponent;
import convex.lattice.TransactionalComponent;
import convex.lattice.cursor.AForkableCursor;

/**
 * Component at one mailbox, {@code [:mail did]}: an index of entry registers.
 *
 * <p>Anyone holding this view may {@link #deposit}. Opening and receiving are the
 * recipient's: {@link #open} needs the decryptors for the recipient's keys and
 * {@link #receive} needs a context holding a signer authorised for the DID. Every
 * write goes through the mailbox lattice, so a receipted entry absorbs a later
 * deposit and an unauthorised receipt is dropped locally exactly as a peer would
 * drop it.</p>
 */
public final class Mailbox extends ALatticeComponent<Index<ABlob,SignedData<ACell>>>
		implements TransactionalComponent<Index<ABlob,SignedData<ACell>>,Mailbox> {

	private final MailboxLattice lattice;

	Mailbox(AForkableCursor<Index<ABlob,SignedData<ACell>>> cursor, MailboxLattice lattice) {
		super(cursor);
		this.lattice=lattice;
	}

	private Mailbox(Mailbox source) {
		super(source.lattice,source.cursor);
		this.lattice=source.lattice;
	}

	/** The mailbox DID. */
	public AString did() { return lattice.getDID(); }

	/** Envelopes not yet receipted, in key order (issued-at, then hash). */
	public List<MailEnvelope> deposited() {
		List<MailEnvelope> list=new ArrayList<>();
		Index<ABlob,SignedData<ACell>> index=cursor.get();
		if (index!=null) index.forEach((key,value) -> {
			MailEnvelope envelope=MailEnvelope.parse(value);
			if (envelope!=null) list.add(envelope);
		});
		return list;
	}

	/** Receipts held, in key order. */
	public List<MailReceipt> receipts() {
		List<MailReceipt> list=new ArrayList<>();
		Index<ABlob,SignedData<ACell>> index=cursor.get();
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
		Index<ABlob,SignedData<ACell>> index=cursor.get();
		return index!=null && MailReceipt.parse(index.get(key))!=null;
	}

	/**
	 * Deposits an envelope addressed to this mailbox.
	 *
	 * @param envelope Envelope whose {@code to} is this mailbox's DID
	 */
	public void deposit(MailEnvelope envelope) {
		if (!did().equals(envelope.to())) {
			throw new IllegalArgumentException("Envelope is addressed to "+envelope.to()+", not "+did());
		}
		merge(envelope.key(),envelope.signed());
	}

	/**
	 * Receipts envelopes on behalf of the mailbox owner. Each receipt replaces its
	 * envelope in the mailbox.
	 *
	 * @param envelopes Envelopes the recipient has taken
	 * @return The receipts written
	 * @throws IllegalStateException If the context holds no signer authorised for the DID
	 */
	public List<MailReceipt> receive(Collection<MailEnvelope> envelopes) {
		List<MailReceipt> receipts=new ArrayList<>();
		for (MailEnvelope envelope:envelopes) {
			MailReceipt receipt=MailReceipt.create(getContext(),did(),envelope);
			if (receipt==null) throw new IllegalStateException("No signer authorised for mailbox "+did());
			merge(receipt.key(),receipt.signed());
			receipts.add(receipt);
		}
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
				&& lattice.getAuthorizer().safeAuthorises(envelope.from(),envelope.signer());
			messages.add(new MailMessage(envelope.hash(),envelope.to(),envelope.from(),envelope.signer(),
				fromAuthorised,envelope.issuedAt(),payload,envelope.encrypted()));
		}
		return messages;
	}

	private void merge(ABlob key, SignedData<ACell> value) {
		Index<ABlob,SignedData<ACell>> single=Index.create(key,value);
		cursor.updateAndGet(index -> lattice.merge((index==null)?lattice.zero():index,single));
	}

	/** Creates an isolated working copy, merged back with the mailbox lattice on sync. */
	public Mailbox fork() {
		return configured(new Mailbox(this));
	}

	@Override
	public Mailbox withCursor(AForkableCursor<Index<ABlob,SignedData<ACell>>> cursor) {
		return configured(new Mailbox(cursor,lattice));
	}
}
