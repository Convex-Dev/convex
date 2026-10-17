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
import convex.core.util.MergeFunction;
import convex.lattice.ALattice;
import convex.lattice.LatticeContext;
import convex.lattice.generic.IndexLattice;

/**
 * The {@code :mail} region: a map from recipient canonical base DID to that DID's
 * mailbox, an {@code Index} of entry registers keyed by {@code issued-at ‖ hash}.
 *
 * <p>Each mailbox merges with an {@link IndexLattice} over a {@link MailEntryLattice}
 * bound to its DID, so the region's own rule is only the keyed dispatch to the right
 * mailbox and the rejection of keys that are not canonical DIDs. The authoriser bound
 * here decides which signers may issue receipts for each DID; {@link Mail#LATTICE}
 * binds the {@code did:key}-only rule.</p>
 */
public final class MailLattice extends ALattice<AHashMap<ACell,Index<ABlob,SignedData<ACell>>>> {

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

	/** The entry register for one mailbox, or null if the key is not a canonical DID. */
	public MailEntryLattice entries(ACell did) {
		if (!(did instanceof AString s) || !DID.isCanonicalBase(s)) return null;
		return MailEntryLattice.create(s,authorizer);
	}

	/** The lattice of one mailbox, or null if the key is not a canonical DID. */
	public IndexLattice<ABlob,SignedData<ACell>> forMailbox(ACell did) {
		MailEntryLattice entries=entries(did);
		return (entries==null)?null:IndexLattice.create(entries);
	}

	@Override
	public AHashMap<ACell,Index<ABlob,SignedData<ACell>>> merge(
			AHashMap<ACell,Index<ABlob,SignedData<ACell>>> own,
			AHashMap<ACell,Index<ABlob,SignedData<ACell>>> other) {
		if (other==null) return own;
		if (own==null) own=zero();
		MergeFunction<Index<ABlob,SignedData<ACell>>> merger=new MergeFunction<>() {
			@Override
			public Index<ABlob,SignedData<ACell>> merge(Index<ABlob,SignedData<ACell>> a,
					Index<ABlob,SignedData<ACell>> b) {
				throw new IllegalStateException("Mailbox merge requires its DID");
			}

			@Override
			public Index<ABlob,SignedData<ACell>> merge(Object key, Index<ABlob,SignedData<ACell>> a,
					Index<ABlob,SignedData<ACell>> b) {
				if (b==null) return a;
				IndexLattice<ABlob,SignedData<ACell>> mailbox=(key instanceof ACell cell)?forMailbox(cell):null;
				if (mailbox==null || !mailbox.checkForeign(b)) return a;
				return mailbox.merge(a,b);
			}
		};
		return own.mergeDifferences(other,merger);
	}

	@Override
	public AHashMap<ACell,Index<ABlob,SignedData<ACell>>> merge(LatticeContext context,
			AHashMap<ACell,Index<ABlob,SignedData<ACell>>> own,
			AHashMap<ACell,Index<ABlob,SignedData<ACell>>> other) {
		// Receipt authorisation is bound to this instance; the context adds nothing here
		return merge(own,other);
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
