package convex.lattice.mail;

import convex.auth.did.DID;
import convex.auth.did.DIDKeyAuthorizer;
import convex.core.data.ABlob;
import convex.core.data.ACell;
import convex.core.data.AString;
import convex.core.data.Blobs;
import convex.core.data.Index;
import convex.core.data.SignedData;
import convex.core.util.MergeFunction;
import convex.lattice.ALattice;
import convex.lattice.LatticeContext;

/**
 * One mailbox: an {@code Index} from entry key {@code issued-at ‖ hash} to the entry
 * register for that key, bound to the mailbox DID.
 *
 * <p>The register's rule needs its key, so the index merges through the keyed
 * {@link MergeFunction} overload rather than the two-argument form a plain
 * {@code IndexLattice} uses, and {@link #path} returns the {@link MailEntryLattice}
 * bound to a key, as an owner lattice returns an owner-bound signed lattice. An entry
 * whose key does not have the shape of an entry key has no register and is dropped.
 * Entries already held are not re-validated: they passed this rule when they were
 * merged or written.</p>
 */
public final class MailboxLattice extends ALattice<Index<ABlob,SignedData<ACell>>> {

	private final AString did;
	private final DIDKeyAuthorizer authorizer;
	private final MergeFunction<SignedData<ACell>> merger;

	private MailboxLattice(AString did, DIDKeyAuthorizer authorizer) {
		this.did=did;
		this.authorizer=authorizer;
		this.merger=new MergeFunction<>() {
			@Override
			public SignedData<ACell> merge(SignedData<ACell> own, SignedData<ACell> other) {
				throw new IllegalStateException("Mailbox entry merge requires its key");
			}

			@Override
			public SignedData<ACell> merge(Object key, SignedData<ACell> own, SignedData<ACell> other) {
				if (other==null) return own;
				MailEntryLattice entry=(key instanceof ACell cell)?entry(cell):null;
				return (entry==null)?own:entry.merge(own,other);
			}
		};
	}

	/**
	 * Creates the lattice of one mailbox.
	 *
	 * @param did Mailbox canonical base DID
	 * @param authorizer Rule deciding which keys may sign receipts for the DID
	 * @return Mailbox lattice bound to the DID
	 */
	public static MailboxLattice create(AString did, DIDKeyAuthorizer authorizer) {
		if (!DID.isCanonicalBase(did)) throw new IllegalArgumentException("Mailbox must be a canonical base DID");
		if (authorizer==null) throw new IllegalArgumentException("Authorizer must not be null");
		return new MailboxLattice(did,authorizer);
	}

	public AString getDID() { return did; }
	public DIDKeyAuthorizer getAuthorizer() { return authorizer; }

	/** The register for one entry, or null if the key is not an entry key. */
	public MailEntryLattice entry(ACell key) {
		if (!MailEntryLattice.isKey(key)) return null;
		return MailEntryLattice.create(did,(ABlob)key,authorizer);
	}

	@Override
	public Index<ABlob,SignedData<ACell>> merge(Index<ABlob,SignedData<ACell>> own,
			Index<ABlob,SignedData<ACell>> other) {
		if (other==null) return own;
		if (own==null) own=zero();
		return own.mergeDifferences(other,merger);
	}

	@Override
	public Index<ABlob,SignedData<ACell>> merge(LatticeContext context,
			Index<ABlob,SignedData<ACell>> own, Index<ABlob,SignedData<ACell>> other) {
		// Receipt authorisation is bound to this instance; the context adds nothing here
		return merge(own,other);
	}

	@Override
	public Index<ABlob,SignedData<ACell>> zero() {
		return Index.none();
	}

	@Override
	public boolean checkForeign(Index<ABlob,SignedData<ACell>> value) {
		return value instanceof Index;
	}

	@SuppressWarnings("unchecked")
	@Override
	public <T extends ACell> ALattice<T> path(ACell childKey) {
		return (ALattice<T>)entry(childKey);
	}

	/** Resolves an entry key given as a blob or as its hex string. */
	@Override
	public ACell resolveKey(ACell key) {
		if (key instanceof AString s) key=Blobs.fromHex(s);
		return MailEntryLattice.isKey(key)?key:null;
	}
}
