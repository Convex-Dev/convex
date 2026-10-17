package convex.lattice.mail;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import convex.auth.did.DIDKeyAuthorizer;
import convex.core.data.ABlob;
import convex.core.data.ACell;
import convex.core.data.AHashMap;
import convex.core.data.AString;
import convex.core.data.Hash;
import convex.core.data.Index;
import convex.core.data.Keyword;
import convex.core.data.SignedData;
import convex.lattice.ALatticeApplication;
import convex.lattice.ALatticeComponent;
import convex.lattice.LatticeContext;
import convex.lattice.TransactionalComponent;
import convex.lattice.cursor.AForkableCursor;
import convex.lattice.cursor.Cursors;

/**
 * Component at the {@code :mail} region: the map of mailboxes.
 *
 * <p>Deposits go through {@link #send}, which also records the envelope in a local
 * outbox until a receipt for it is seen; {@link #sync} redeposits outstanding envelopes
 * first, so a deposit refused or forgotten elsewhere is delayed rather than lost. The
 * outbox is sender-local state, not part of the region.</p>
 *
 * <pre>{@code
 * Mail mail = Mail.create(LatticeContext.create(null, aliceKey));
 * MailEnvelope envelope = MailEnvelope.seal(aliceKey, bobDid, bobKey.getAccountKey(),
 *     aliceDid, now, Maps.of(MailEnvelope.CONTENT, Strings.create("hello")));
 * mail.send(envelope);
 *
 * Mailbox inbox = mail.mailbox(bobDid);           // Bob's view, with a context holding Bob's key
 * List<MailMessage> messages = inbox.open(ECIES.createDecryptor(bobKey));
 * inbox.receive(inbox.deposited());               // receipts replace the envelopes
 * }</pre>
 */
public final class Mail extends ALatticeComponent<AHashMap<ACell,Index<ABlob,SignedData<ACell>>>>
		implements TransactionalComponent<AHashMap<ACell,Index<ABlob,SignedData<ACell>>>,Mail> {

	/** Keyword of the mail region in a root lattice. */
	public static final Keyword KEY_MAIL=Keyword.intern("mail");

	/** Region lattice admitting receipts from {@code did:key} owners only. */
	public static final MailLattice LATTICE=MailLattice.create(DIDKeyAuthorizer.CONVEX);

	private final MailLattice lattice;
	private final ConcurrentHashMap<Hash,MailEnvelope> outbox=new ConcurrentHashMap<>();

	private Mail(AForkableCursor<AHashMap<ACell,Index<ABlob,SignedData<ACell>>>> cursor, MailLattice lattice) {
		super(cursor);
		this.lattice=lattice;
	}

	private Mail(Mail source) {
		super(source.lattice,source.cursor);
		this.lattice=source.lattice;
	}

	/** Creates a standalone region over an in-memory root, with the default lattice. */
	public static Mail create(LatticeContext context) {
		return create(LATTICE,context);
	}

	/** Creates a standalone region over an in-memory root. */
	public static Mail create(MailLattice lattice, LatticeContext context) {
		Mail mail=new Mail(Cursors.root(lattice.zero()),lattice);
		mail.setContext(context);
		return mail;
	}

	/** Connects to the region cursor an application hands down. */
	public static Mail connect(AForkableCursor<AHashMap<ACell,Index<ABlob,SignedData<ACell>>>> cursor,
			MailLattice lattice, LatticeContext context) {
		if (cursor==null) throw new IllegalArgumentException("Mail cursor must not be null");
		if (lattice==null) throw new IllegalArgumentException("Mail lattice must not be null");
		Mail mail=new Mail(cursor,lattice);
		mail.setContext(context);
		return mail;
	}

	/** Connects to the {@code :mail} region of an application whose root includes it. */
	public static Mail connect(ALatticeApplication<?> application, MailLattice lattice, LatticeContext context) {
		if (application==null) throw new IllegalArgumentException("Application must not be null");
		return connect(application.resolve(KEY_MAIL),lattice,(context==null)?application.getContext():context);
	}

	public MailLattice getLattice() { return lattice; }

	/**
	 * Gets a mailbox view. Anyone may deposit through it; receipts need a context
	 * holding a signer authorised for the DID.
	 *
	 * @param did Recipient canonical base DID
	 * @return Mailbox component at {@code [:mail did]}
	 */
	public Mailbox mailbox(AString did) {
		MailboxLattice mailbox=lattice.forMailbox(did);
		if (mailbox==null) throw new IllegalArgumentException("Mailbox must be a canonical base DID");
		return configured(new Mailbox(resolve(cursor,lattice,did),mailbox));
	}

	/**
	 * Deposits an envelope into its recipient's mailbox and keeps it in the outbox
	 * until a receipt is seen.
	 *
	 * @param envelope Envelope to send
	 * @return Envelope hash
	 */
	public Hash send(MailEnvelope envelope) {
		mailbox(envelope.to()).deposit(envelope);
		outbox.put(envelope.hash(),envelope);
		return envelope.hash();
	}

	/** Hashes of sent envelopes with no receipt yet. */
	public Set<Hash> outbox() {
		outbox.values().removeIf(e -> mailbox(e.to()).isReceived(e));
		return Set.copyOf(outbox.keySet());
	}

	/** Deposits every outstanding envelope again. A receipted entry absorbs the deposit. */
	public void redeposit() {
		for (MailEnvelope envelope:outbox.values()) {
			Mailbox mailbox=mailbox(envelope.to());
			if (!mailbox.isReceived(envelope)) mailbox.deposit(envelope);
		}
	}

	/**
	 * Redeposits outstanding envelopes, then publishes as the base component does. For
	 * a lightweight view the redeposit is the only effect.
	 */
	@Override
	public AHashMap<ACell,Index<ABlob,SignedData<ACell>>> sync() {
		redeposit();
		return super.sync();
	}

	/** Creates an isolated working copy, merged back with the region lattice on sync. */
	public Mail fork() {
		return configured(new Mail(this));
	}

	@Override
	public Mail withCursor(AForkableCursor<AHashMap<ACell,Index<ABlob,SignedData<ACell>>>> cursor) {
		return configured(new Mail(cursor,lattice));
	}
}
