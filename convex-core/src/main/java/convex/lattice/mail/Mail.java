package convex.lattice.mail;

import java.util.Objects;
import java.util.function.BiFunction;

import convex.auth.did.DID;
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
import convex.core.lang.RT;
import convex.core.util.MergeFunction;
import convex.lattice.ALatticeComponent;
import convex.lattice.AMapComponent;
import convex.lattice.TransactionalComponent;
import convex.lattice.cursor.AForkableCursor;
import convex.lattice.cursor.Cursors;

/**
 * Component at the {@code :mail} region: the map from recipient canonical base DID
 * to that DID's {@link Mailbox}.
 *
 * <p>{@link #send} atomically deposits one envelope in its recipient's mailbox.
 * That entry is the complete send state: replication propagates it, and a
 * recipient's receipt replaces it at the same key. There is no separate sender
 * buffer. Pending envelopes and receipts are inspected through {@link #mailbox}.</p>
 *
 * <p>An incoming region merges mailbox by mailbox, each by its mailbox's rule. A key
 * that is not a canonical base DID has no mailbox and is dropped, as is a delivery
 * at a path beneath one and a received mailbox that is not an index. A delivered
 * region that is not a hash map is dropped. The
 * authoriser bound here decides which signers may issue receipts for each DID;
 * without one, only a {@code did:key} owner may.</p>
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
public final class Mail extends AMapComponent<ACell,Index<ABlob,SignedData<ACell>>,AHashMap<ACell,Index<ABlob,SignedData<ACell>>>>
		implements TransactionalComponent<AHashMap<ACell,Index<ABlob,SignedData<ACell>>>,Mail> {

	/** Keyword of the mail region in a root. */
	public static final Keyword KEY_MAIL=Keyword.intern("mail");

	private final DIDKeyAuthorizer authorizer;
	private final BiFunction<ACell,ACell,SignedData<ACell>> signer;

	private Mail(AForkableCursor<AHashMap<ACell,Index<ABlob,SignedData<ACell>>>> cursor, DIDKeyAuthorizer authorizer,
			BiFunction<ACell,ACell,SignedData<ACell>> signer) {
		super(cursor);
		this.authorizer=Objects.requireNonNull(authorizer,"Authorizer must not be null");
		this.signer=signer;
	}

	/** Creates a relay which may deposit envelopes but cannot author receipts. */
	public static Mail create() { return create(null); }

	/**
	 * Creates a standalone region admitting receipts from {@code did:key} owners only,
	 * with an owner-aware receipt signer; null means no authoring capability.
	 */
	public static Mail create(BiFunction<ACell,ACell,SignedData<ACell>> signer) {
		return create(DIDKeyAuthorizer.CONVEX,signer);
	}

	/** Creates a standalone region with explicit DID verification and multi-owner signing. */
	public static Mail create(DIDKeyAuthorizer authorizer,BiFunction<ACell,ACell,SignedData<ACell>> signer) {
		return new Mail(Cursors.root(Maps.empty()),authorizer,signer);
	}

	/**
	 * Binds a region to the place its owner gives it, admitting receipts from
	 * {@code did:key} owners only, with an owner-aware receipt signer; null means no
	 * authoring capability. Forks retain both.
	 */
	public static Mail connect(AForkableCursor<AHashMap<ACell,Index<ABlob,SignedData<ACell>>>> cursor,
			BiFunction<ACell,ACell,SignedData<ACell>> signer) {
		return connect(cursor,DIDKeyAuthorizer.CONVEX,signer);
	}

	/**
	 * Binds a region to the place its owner gives it, with explicit verification and
	 * receipt signing. Forks retain both.
	 */
	public static Mail connect(AForkableCursor<AHashMap<ACell,Index<ABlob,SignedData<ACell>>>> cursor,
			DIDKeyAuthorizer authorizer,BiFunction<ACell,ACell,SignedData<ACell>> signer) {
		return new Mail(cursor,authorizer,signer);
	}

	/**
	 * Gets a mailbox view. Anyone may deposit through it; receipts need a signer
	 * authorised for the DID. One signer may serve multiple DIDs; null results mean
	 * the requested owner is unavailable. Shared signers must support concurrent use.
	 *
	 * @param did Recipient canonical base DID
	 * @return Mailbox component at {@code [:mail did]}
	 */
	public Mailbox mailbox(AString did) {
		if (!isMailbox(did)) throw new IllegalArgumentException("Mailbox must be a canonical base DID");
		return new Mailbox(entry(did),did,authorizer,signer);
	}

	@Override
	protected AHashMap<ACell,Index<ABlob,SignedData<ACell>>> requireMap(ACell value) {
		if (value==null) return null;
		AHashMap<ACell,Index<ABlob,SignedData<ACell>>> map=RT.ensureHashMap(value);
		if (map==null) throw new IllegalStateException("Mail region must be a hash map");
		return map;
	}

	@Override
	protected AHashMap<ACell,Index<ABlob,SignedData<ACell>>> createContainer() {
		return Maps.empty();
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

	/**
	 * Merges an incoming region into this one, mailbox by mailbox.
	 *
	 * @param incoming Incoming region value
	 * @return Current working value
	 */
	@Override
	public AHashMap<ACell,Index<ABlob,SignedData<ACell>>> merge(AHashMap<ACell,Index<ABlob,SignedData<ACell>>> incoming) {
		return cursor().updateAndGet(held -> mergeMailboxes(held,incoming));
	}

	/** Drops a delivered region that is not a hash map; routes other deliveries as every component does. */
	@Override
	public AHashMap<ACell,Index<ABlob,SignedData<ACell>>> mergeAt(ACell value,ACell... path) {
		if (path.length==0 && value!=null && !(value instanceof AHashMap)) return cursor().getOrDefault(null);
		return super.mergeAt(value,path);
	}

	/** Resolves the mailbox at a canonical base DID, for deliveries beneath it. */
	@SuppressWarnings("unchecked")
	@Override
	protected <T extends ACell> ALatticeComponent<T> child(ACell key) {
		return isMailbox(key)?(ALatticeComponent<T>)(ALatticeComponent<?>)mailbox((AString)key):null;
	}

	/** Drops a delivery beneath a key that is not a mailbox, as such a key is dropped from an incoming region. */
	@Override
	protected AHashMap<ACell,Index<ABlob,SignedData<ACell>>> mergeAtPath(ACell value,ACell... path) {
		return cursor().getOrDefault(null);
	}

	/** Creates an isolated working copy, merged back mailbox by mailbox on sync. */
	public Mail fork() {
		return rebind(storage.fork());
	}

	@Override
	public Mail rebind(AForkableCursor<AHashMap<ACell,Index<ABlob,SignedData<ACell>>>> cursor) {
		return new Mail(cursor,authorizer,signer);
	}

	/** Reconciles this component's draft mailbox by mailbox. */
	@Override
	protected AHashMap<ACell,Index<ABlob,SignedData<ACell>>> reconcile(
			AHashMap<ACell,Index<ABlob,SignedData<ACell>>> own,
			AHashMap<ACell,Index<ABlob,SignedData<ACell>>> other) {
		return mergeMailboxes(own,other);
	}

	/**
	 * Merges an incoming region into a held one. Only mailboxes that differ are
	 * visited. An absent held region merges as an empty one, and a held region that
	 * takes nothing is returned itself.
	 */
	@SuppressWarnings({"unchecked","rawtypes"})
	private AHashMap<ACell,Index<ABlob,SignedData<ACell>>> mergeMailboxes(
			AHashMap<ACell,Index<ABlob,SignedData<ACell>>> own,
			AHashMap<ACell,Index<ABlob,SignedData<ACell>>> incoming) {
		if (incoming==null) return own;
		// An absent region is merged against an empty one, so every incoming mailbox meets the rule
		AHashMap<ACell,Index<ABlob,SignedData<ACell>>> held=(own==null)?Maps.empty():own;
		return ((AHashMap)held).mergeDifferences(incoming,new MergeFunction<ACell>() {
			@Override
			public ACell merge(ACell a,ACell b) {
				throw new IllegalStateException("Mail merge requires the mailbox key");
			}

			@Override
			public ACell merge(Object key,ACell a,ACell b) {
				if (b==null || !isMailbox(key) || !(b instanceof Index)) return a;
				return Mailbox.mergeEntries((AString)key,authorizer,(Index<ABlob,SignedData<ACell>>)a,b);
			}
		});
	}

	/** True iff a key is a canonical base DID, which alone has a mailbox. */
	private static boolean isMailbox(Object key) {
		return (key instanceof AString did) && DID.isCanonicalBase(did);
	}
}
