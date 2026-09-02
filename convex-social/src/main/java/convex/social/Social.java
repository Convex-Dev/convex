package convex.social;

import convex.auth.did.DID;
import convex.auth.did.DIDKeyAuthorizer;
import convex.core.crypto.AKeyPair;
import convex.core.data.ACell;
import convex.core.data.AccountKey;
import convex.core.data.AHashMap;
import convex.core.data.Index;
import convex.core.data.Keyword;
import convex.core.data.Maps;
import convex.core.data.SignedData;
import convex.core.cvm.Keywords;
import convex.lattice.ALatticeComponent;
import convex.lattice.LatticeContext;
import convex.lattice.cursor.ALatticeCursor;
import convex.lattice.cursor.Cursors;
import convex.lattice.generic.OwnerLattice;

/**
 * Cursor-based application layer for the Convex social network.
 *
 * <p>{@code Social} wraps a lattice cursor at the {@code :social} level
 * (an {@link OwnerLattice} mapping canonical DIDs to signed per-user state)
 * and provides domain-specific accessors for users, feeds, and follows.</p>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * // Standalone
 * Social social = Social.create(myKeyPair);
 * AString myDid = DID.forKey(myKeyPair.getAccountKey());
 * social.user(myDid).feed().post("Hello!");
 *
 * // Connected beneath a hosted application component
 * Social social = Social.connect(application, myKeyPair);
 *
 * // Fork for batch operations
 * Social forked = social.fork();
 * forked.user(myKey).feed().post("Post 1");
 * forked.user(myKey).feed().post("Post 2");
 * forked.sync();
 * }</pre>
 *
 * <h2>Integration</h2>
 *
 * <p>Convex Social is an application layered on P2P infrastructure: it supplies the
 * {@code :social} region and nothing else, leaving peer discovery, node identity and
 * value propagation to convex-p2p.</p>
 *
 * <p>There is no separate social node to run. {@code P2PNode} is the only node server,
 * and serves {@code :social} by default as part of {@code P2PLattice.NODE_ROOT}. An
 * operator who does not want social passes {@code P2PLattice.ROOT} instead and still runs
 * a fully capable discovery node. Region sets need not match across a network: a node
 * that does not serve {@code :social} ignores the region rather than failing on it.</p>
 *
 * <p>For a node that also wants convex-core's application regions ({@code :data},
 * {@code :fs}, {@code :kv}, {@code :queue}), composing onto {@code Lattice.ROOT} remains
 * equally valid:</p>
 * <pre>{@code
 * KeyedLattice root = Lattice.ROOT.addLattice(Social.KEY_SOCIAL, Social.SOCIAL_LATTICE);
 * }</pre>
 */
public class Social extends ALatticeComponent<
		AHashMap<ACell, SignedData<Index<Keyword, ACell>>>> {

	/**
	 * Keyword for the social section in a node's root lattice.
	 */
	public static final Keyword KEY_SOCIAL = Keyword.intern("social");

	/**
	 * The social lattice: OwnerLattice mapping canonical DIDs to signed per-user state.
	 *
	 * <p>Each owner's value is wrapped in {@code SignedData}; its signer must be an
	 * Ed25519 key authorised for the DID. The inner value is a {@link SocialLattice}
	 * containing {@code :feed}, {@code :profile}, and {@code :following}.</p>
	 */
	public static final OwnerLattice<Index<Keyword, ACell>> SOCIAL_LATTICE =
		new DIDOwnerLattice();

	/** Owner boundary which admits canonical DID keys only. */
	private static final class DIDOwnerLattice
			extends OwnerLattice<Index<Keyword,ACell>> {

		DIDOwnerLattice() {
			super(SocialLattice.INSTANCE);
		}

		@Override
		public AHashMap<ACell,SignedData<Index<Keyword,ACell>>> merge(
				AHashMap<ACell,SignedData<Index<Keyword,ACell>>> own,
				AHashMap<ACell,SignedData<Index<Keyword,ACell>>> other) {
			return super.merge(own,sanitise(other,null));
		}

		@Override
		public AHashMap<ACell,SignedData<Index<Keyword,ACell>>> merge(
				LatticeContext context,
				AHashMap<ACell,SignedData<Index<Keyword,ACell>>> own,
				AHashMap<ACell,SignedData<Index<Keyword,ACell>>> other) {
			return super.merge(context,own,sanitise(other,context));
		}

		@SuppressWarnings({"unchecked","rawtypes"})
		private AHashMap<ACell,SignedData<Index<Keyword,ACell>>> sanitise(
				AHashMap<ACell,SignedData<Index<Keyword,ACell>>> other,
				LatticeContext context) {
			if (other==null) return null;
			AHashMap<ACell,SignedData<Index<Keyword,ACell>>> clean=Maps.empty();
			for (Object item:((AHashMap)other).entrySet()) {
				java.util.Map.Entry<?,?> entry=(java.util.Map.Entry<?,?>)item;
				if (!(entry.getKey() instanceof convex.core.data.AString did)
						|| !DID.isCanonicalBase(did)) continue;
				if (!(entry.getValue() instanceof SignedData<?> signed)) continue;
				// did:key is self-certifying. Indirect DIDs must never fall through
				// LatticeContext's compatibility-lenient no-verifier behaviour.
				if (DID.keyFromDID(did)==null
						&& (context==null || context.getOwnerVerifier()==null)) continue;
				clean=clean.assoc(did,(SignedData<Index<Keyword,ACell>>)signed);
			}
			return clean;
		}
	}

	Social(ALatticeCursor<AHashMap<ACell, SignedData<Index<Keyword, ACell>>>> cursor) {
		super(cursor);
	}

	Social(ALatticeComponent<?> parent,
			ALatticeCursor<AHashMap<ACell, SignedData<Index<Keyword, ACell>>>> cursor) {
		super(parent,cursor);
	}

	/**
	 * Creates a standalone Social instance with its own cursor.
	 *
	 * @param keyPair Key pair for signing updates
	 * @return New Social instance
	 */
	public static Social create(AKeyPair keyPair) {
		return create(LatticeContext.create(null,keyPair,DIDKeyAuthorizer.CONVEX::verifiesOwner));
	}

	/**
	 * Creates a standalone Social instance using an application policy.
	 *
	 * @param context Context supplying signing and timestamp policy
	 * @return New Social instance
	 */
	public static Social create(LatticeContext context) {
		ALatticeCursor<AHashMap<ACell, SignedData<Index<Keyword, ACell>>>> cursor =
			Cursors.createLattice(SOCIAL_LATTICE);
		cursor.setContext(context);
		return new Social(cursor);
	}

	/**
	 * Connects to an existing root lattice cursor by navigating to {@code :social}.
	 *
	 * <p>The root cursor is typically held by a {@code NodeServer} for lattice
	 * push/pull. Writes through this Social instance propagate up to the root.</p>
	 *
	 * @param rootCursor Root lattice cursor (e.g. from NodeServer)
	 * @param keyPair Key pair for signing updates
	 * @return Social instance connected to the root cursor
	 */
	public static Social connect(ALatticeCursor<?> rootCursor, AKeyPair keyPair) {
		return connect(rootCursor,LatticeContext.create(
			null,keyPair,DIDKeyAuthorizer.CONVEX::verifiesOwner));
	}

	/** Connects to an existing root cursor using an application context policy. */
	public static Social connect(ALatticeCursor<?> rootCursor,LatticeContext context) {
		ALatticeCursor<AHashMap<ACell, SignedData<Index<Keyword, ACell>>>> socialCursor =
			rootCursor.path(KEY_SOCIAL);
		socialCursor.setContext(context);
		return new Social(socialCursor);
	}

	/**
	 * Connects to the {@code :social} region beneath a containing component.
	 * Component-level policy such as persistence delegates through the supplied
	 * parent while cursor operations remain scoped to the social path.
	 *
	 * @param parent Containing lattice component
	 * @param keyPair Key pair for signing updates
	 * @return Social instance connected beneath the parent component
	 */
	public static Social connect(ALatticeComponent<?> parent, AKeyPair keyPair) {
		return connect(parent,LatticeContext.create(
			null,keyPair,DIDKeyAuthorizer.CONVEX::verifiesOwner));
	}

	/** Connects beneath a component using an explicit application policy. */
	public static Social connect(ALatticeComponent<?> parent,LatticeContext context) {
		Social social=connect(parent);
		social.cursor.setContext(context);
		return social;
	}

	/**
	 * Connects to the {@code :social} region beneath a containing component,
	 * inheriting its live lattice context and persistence policy.
	 *
	 * @param parent Containing application component
	 * @return Social region component
	 */
	public static Social connect(ALatticeComponent<?> parent) {
		if (parent==null) throw new IllegalArgumentException("Parent component must not be null");
		ALatticeCursor<AHashMap<ACell, SignedData<Index<Keyword, ACell>>>> socialCursor =
			parent.cursor().path(KEY_SOCIAL);
		return new Social(parent,socialCursor);
	}

	/**
	 * Gets a user view by navigating through the owner/signing boundary.
	 *
	 * <p>The returned {@link SocialUser} wraps a cursor at the per-user
	 * {@link SocialLattice} level. Writes are automatically signed by the context's
	 * signing policy, which is asked for a signer authorised for {@code ownerDid} —
	 * not necessarily its primary account.</p>
	 *
	 * @param ownerDid the user's canonical base DID
	 * @return SocialUser for the specified owner
	 */
	public SocialUser user(convex.core.data.AString ownerDid) {
		if (!DID.isCanonicalBase(ownerDid)) {
			throw new IllegalArgumentException("Social owner must be a canonical base DID");
		}
		ALatticeCursor<Index<Keyword, ACell>> userCursor =
			cursor.path(ownerDid, Keywords.VALUE);
		return new SocialUser(this,userCursor,ownerDid);
	}

	/** Convenience migration overload mapping an Ed25519 key to its {@code did:key}. */
	public SocialUser user(AccountKey ownerKey) {
		if (ownerKey==null) throw new IllegalArgumentException("Owner key must not be null");
		return user(DID.forKey(ownerKey));
	}

	/**
	 * Creates a forked copy for independent operation.
	 * Changes don't affect the parent until {@link #sync()}.
	 *
	 * @return Forked Social instance
	 */
	public Social fork() {
		return new Social(parent(),cursor.fork());
	}

}
