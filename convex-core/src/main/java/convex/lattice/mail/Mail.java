package convex.lattice.mail;

import java.util.Objects;
import java.util.function.BiFunction;

import convex.auth.did.DIDKeyAuthorizer;
import convex.core.data.ABlob;
import convex.core.data.ACell;
import convex.core.data.AHashMap;
import convex.core.data.AString;
import convex.core.data.Hash;
import convex.core.data.Index;
import convex.core.data.Keyword;
import convex.core.data.Maps;
import convex.core.data.SignedData;
import convex.lattice.ALatticeApplication;
import convex.lattice.ALatticeComponent;
import convex.lattice.TransactionalComponent;
import convex.lattice.cursor.AForkableCursor;
import convex.lattice.cursor.Cursors;

/**
 * Component at the {@code :mail} region: the map of mailboxes.
 *
 * <p>{@link #send} atomically deposits one envelope in its recipient's mailbox.
 * That lattice entry is the complete send state: replication propagates it, and
 * a recipient's receipt replaces it at the same key. There is no separate sender
 * buffer. Pending envelopes and receipts are inspected through {@link #mailbox}.</p>
 *
 * <p>Sends participate in an enclosing transaction or fork through their supplied
 * cursor. Sync has the ordinary component behaviour: a live view does nothing,
 * while a fork publishes its working state. Application publication and host
 * persistence remain separate from the local send.</p>
 *
 * <pre>{@code
 * Mail mail = Mail.create((did,value) -> bobKey.signData(value));
 * MailEnvelope envelope = MailEnvelope.seal(aliceKey, bobDid, bobKey.getAccountKey(),
 *     aliceDid, now, Maps.of(MailEnvelope.CONTENT, Strings.create("hello")));
 * mail.send(envelope);
 *
 * Mailbox inbox = mail.mailbox(bobDid);           // Bob's view, using the supplied receipt signer
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
	private final BiFunction<ACell,ACell,SignedData<ACell>> signer;

	private Mail(AForkableCursor<AHashMap<ACell,Index<ABlob,SignedData<ACell>>>> cursor, MailLattice lattice, BiFunction<ACell,ACell,SignedData<ACell>> signer) {
		super(cursor);
		this.lattice=Objects.requireNonNull(lattice);
		this.signer=signer;
	}

	/** Creates a relay which may deposit envelopes but cannot author receipts. */
	public static Mail create() { return create(null); }

	/** Creates a region with an owner-aware receipt signer; null means no authoring capability. */
	public static Mail create(BiFunction<ACell,ACell,SignedData<ACell>> signer) {
		return create(LATTICE,signer);
	}

	/** Creates a region with explicit DID verification and multi-owner signing. */
	public static Mail create(MailLattice lattice,BiFunction<ACell,ACell,SignedData<ACell>> signer) {
		return new Mail(Cursors.root(lattice.zero()),lattice,signer);
	}

	/** Connects a region with explicit verification and receipt signing. Forks retain both. */
	public static Mail connect(AForkableCursor<AHashMap<ACell,Index<ABlob,SignedData<ACell>>>> cursor,
			MailLattice lattice,BiFunction<ACell,ACell,SignedData<ACell>> signer) {
		return new Mail(cursor,lattice,signer);
	}

	/** Connects to an application's mail region without inheriting any policy. */
	public static Mail connect(ALatticeApplication<Index<Keyword,ACell>> application,MailLattice lattice,
			BiFunction<ACell,ACell,SignedData<ACell>> signer) {
		return connect(application.cursor().withDefault(Index.none()).path(KEY_MAIL),lattice,signer);
	}

	public MailLattice getLattice() { return lattice; }

	/**
	 * Gets a mailbox view. Anyone may deposit through it; receipts need a signer
	 * authorised for the DID. One signer may serve multiple DIDs; null results mean
	 * the requested owner is unavailable. Shared signers must support concurrent use.
	 *
	 * @param did Recipient canonical base DID
	 * @return Mailbox component at {@code [:mail did]}
	 */
	public Mailbox mailbox(AString did) {
		MailboxLattice mailbox=lattice.forMailbox(did);
		if (mailbox==null) throw new IllegalArgumentException("Mailbox must be a canonical base DID");
		return new Mailbox(cursor().withDefault(Maps.empty()).path(did),mailbox,signer);
	}

	/**
	 * Atomically deposits an envelope into its recipient's mailbox. Repeating the
	 * same send is idempotent; an existing receipt absorbs it. The envelope is
	 * prepared before this call so a transaction retry reuses the same signed value.
	 * Completion means a local update, not remote receipt or durable publication.
	 *
	 * @param envelope Envelope to send
	 * @return Envelope hash
	 */
	public Hash send(MailEnvelope envelope) {
		mailbox(envelope.to()).deposit(envelope);
		return envelope.hash();
	}

	/** Creates an isolated working copy, merged back with the region lattice on sync. */
	public Mail fork() {
		return rebind(storage.fork());
	}

	@Override
	public Mail withCursor(AForkableCursor<AHashMap<ACell,Index<ABlob,SignedData<ACell>>>> cursor) {
		return rebind(cursor);
	}

	@Override
	protected Mail rebind(AForkableCursor<AHashMap<ACell,Index<ABlob,SignedData<ACell>>>> cursor) {
		return new Mail(cursor,lattice,signer);
	}

	/** Reconciles this component's draft using its domain merge rule. */
	@Override
	protected AHashMap<ACell,Index<ABlob,SignedData<ACell>>> mergePublication(
			AHashMap<ACell,Index<ABlob,SignedData<ACell>>> own,
			AHashMap<ACell,Index<ABlob,SignedData<ACell>>> other) {
		return lattice.merge(own,other);
	}
}
