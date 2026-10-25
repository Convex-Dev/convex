package convex.lattice.mail;

import convex.auth.did.DID;
import convex.auth.did.DIDKeyAuthorizer;
import convex.core.data.ABlob;
import convex.core.data.ACell;
import convex.core.data.AHashMap;
import convex.core.data.AString;
import convex.core.data.Index;
import convex.core.data.Maps;
import convex.core.data.SignedData;
import convex.lattice.ALattice;
import convex.lattice.generic.AUnionLattice;

/**
 * The {@code :mail} region: a map from recipient canonical base DID to that DID's
 * mailbox, an {@code Index} of entry registers keyed by {@code issued-at ‖ hash}.
 *
 * <p>A union of mailboxes. Each merges with the {@link MailboxLattice} bound to its
 * DID, which {@link #path} returns, and a key that is not a canonical DID has no
 * mailbox and is dropped. The authoriser bound here decides which signers may issue
 * receipts for each DID; {@link Mail#LATTICE} binds the {@code did:key}-only rule.</p>
 */
public final class MailLattice extends AUnionLattice<ACell,Index<ABlob,SignedData<ACell>>,
		AHashMap<ACell,Index<ABlob,SignedData<ACell>>>> {

	private final DIDKeyAuthorizer authorizer;

	private MailLattice(DIDKeyAuthorizer authorizer) {
		this.authorizer=authorizer;
	}

	/**
	 * Creates the region lattice with a receipt signer rule.
	 *
	 * @param authorizer Rule deciding which keys may sign receipts for a DID
	 * @return Region lattice
	 */
	public static MailLattice create(DIDKeyAuthorizer authorizer) {
		if (authorizer==null) throw new IllegalArgumentException("Authorizer must not be null");
		return new MailLattice(authorizer);
	}

	public DIDKeyAuthorizer getAuthorizer() { return authorizer; }

	/** The lattice of one mailbox, or null if the key is not a canonical DID. */
	public MailboxLattice forMailbox(ACell did) {
		if (!(did instanceof AString s) || !DID.isCanonicalBase(s)) return null;
		return MailboxLattice.create(s,authorizer);
	}

	@Override
	public AHashMap<ACell,Index<ABlob,SignedData<ACell>>> zero() {
		return Maps.empty();
	}

	@Override
	public boolean checkForeign(AHashMap<ACell,Index<ABlob,SignedData<ACell>>> value) {
		return value instanceof AHashMap;
	}

	@SuppressWarnings("unchecked")
	@Override
	public <T extends ACell> ALattice<T> path(ACell childKey) {
		return (ALattice<T>)forMailbox(childKey);
	}

	@Override
	public ACell resolveKey(ACell key) {
		return (key instanceof AString s && DID.isCanonicalBase(s))?s:null;
	}
}
