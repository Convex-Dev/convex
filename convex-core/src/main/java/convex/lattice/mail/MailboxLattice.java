package convex.lattice.mail;

import convex.auth.did.DID;
import convex.auth.did.DIDKeyAuthorizer;
import convex.core.data.ABlob;
import convex.core.data.ACell;
import convex.core.data.AString;
import convex.core.data.Blobs;
import convex.core.data.Index;
import convex.core.data.SignedData;
import convex.lattice.ALattice;
import convex.lattice.generic.AUnionLattice;

/**
 * One mailbox: an {@code Index} from entry key {@code issued-at ‖ hash} to the entry
 * register for that key, bound to the mailbox DID.
 *
 * <p>A union of entries. The register's rule needs its key, so {@link #path} returns
 * the {@link MailEntryLattice} bound to the key, as an owner lattice returns an
 * owner-bound signed lattice, and the base merges each entry with it. A key without
 * the shape of an entry key has no register and is dropped.</p>
 */
public final class MailboxLattice extends AUnionLattice<ABlob,SignedData<ACell>,Index<ABlob,SignedData<ACell>>> {

	private final AString did;
	private final DIDKeyAuthorizer authorizer;

	private MailboxLattice(AString did, DIDKeyAuthorizer authorizer) {
		this.did=did;
		this.authorizer=authorizer;
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
