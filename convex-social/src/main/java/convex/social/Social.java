package convex.social;

import convex.auth.did.DID;
import convex.auth.did.DIDKeyAuthorizer;
import convex.core.crypto.AKeyPair;
import convex.core.data.ACell;
import convex.core.data.AHashMap;
import convex.core.data.AccountKey;
import convex.core.data.Index;
import convex.core.data.Keyword;
import convex.core.data.Maps;
import convex.core.data.SignedData;
import convex.lattice.ALatticeApplication;
import convex.lattice.ALatticeComponent;
import convex.lattice.LatticeContext;
import convex.lattice.TransactionalComponent;
import convex.lattice.cursor.AForkableCursor;
import convex.lattice.cursor.Cursors;
import convex.lattice.cursor.SignedCursor;
import convex.lattice.generic.OwnerLattice;

/**
 * Cursor-based application layer for the Convex social network.
 *
 * <p>{@code Social} wraps a cursor at the {@code :social} level (an
 * {@link OwnerLattice} mapping canonical DIDs to signed per-user state) and
 * provides domain-specific accessors for users, feeds, and follows. The cursor
 * knows nothing of the lattice; this component declares {@link #SOCIAL_LATTICE}
 * for construction at its level, the merge of its forks and the owner rule it crosses
 * into a user's signed value with.</p>
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
		AHashMap<ACell, SignedData<Index<Keyword, ACell>>>> implements TransactionalComponent<AHashMap<ACell, SignedData<Index<Keyword, ACell>>>,Social> {

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

	Social(AForkableCursor<AHashMap<ACell, SignedData<Index<Keyword, ACell>>>> cursor) {
		super(cursor);
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
		Social social=new Social(Cursors.root(SOCIAL_LATTICE.zero()));
		social.setContext(context);
		return social;
	}

	/**
	 * Connects the social component to the cursor at its own level, the owner map
	 * under {@code :social}. The application that owns the root hands this cursor
	 * down; the component navigates no further up. Policy starts empty.
	 *
	 * @param socialCursor Cursor at the {@code :social} owner map
	 * @return Social region component
	 */
	public static Social connect(AForkableCursor<AHashMap<ACell, SignedData<Index<Keyword, ACell>>>> socialCursor) {
		if (socialCursor==null) throw new IllegalArgumentException("Social cursor must not be null");
		return new Social(socialCursor);
	}

	/**
	 * Connects to the {@code :social} region of an application's root, with a signing
	 * policy for this instance. The application resolves the region with its own root
	 * lattice.
	 *
	 * @param application Application at the root whose lattice includes {@code :social}
	 * @param keyPair Key pair for signing updates
	 * @return Social instance connected beneath the application
	 */
	public static Social connect(ALatticeApplication<Index<Keyword,ACell>> application, AKeyPair keyPair) {
		return connect(application,LatticeContext.create(
			null,keyPair,DIDKeyAuthorizer.CONVEX::verifiesOwner));
	}

	/** Connects to an application's {@code :social} region using an explicit policy. */
	public static Social connect(ALatticeApplication<Index<Keyword,ACell>> application,LatticeContext context) {
		Social social=connect(application);
		social.setContext(context);
		return social;
	}

	/**
	 * Connects to the {@code :social} region of an application's root, resolving the
	 * application's policy at construction.
	 *
	 * @param application Application at the root whose lattice includes {@code :social}
	 * @return Social region component
	 */
	public static Social connect(ALatticeApplication<Index<Keyword,ACell>> application) {
		if (application==null) throw new IllegalArgumentException("Application must not be null");
		Social social=connect(application.cursor().withDefault(Index.none()).<AHashMap<ACell, SignedData<Index<Keyword, ACell>>>>path(KEY_SOCIAL));
		social.setContext(application.getContext());
		return social;
	}

	/**
	 * Gets a user view, wrapping the owner's slot in its signing boundary.
	 *
	 * <p>This component owns the owner-keyed level, so it is the one place that
	 * crosses into a user's signed value. The returned {@link SocialUser} wraps a
	 * cursor at the per-user {@link SocialLattice} level; writes through it are
	 * signed with the signer the context policy authorises for {@code ownerDid},
	 * which need not be its primary account, under exactly the rule
	 * {@link #SOCIAL_LATTICE} applies to incoming merges.</p>
	 *
	 * @param ownerDid the user's canonical base DID
	 * @return SocialUser for the specified owner
	 */
	public SocialUser user(convex.core.data.AString ownerDid) {
		if (!DID.isCanonicalBase(ownerDid)) {
			throw new IllegalArgumentException("Social owner must be a canonical base DID");
		}
		var rule=SOCIAL_LATTICE.signingFor(ownerDid);
		var policy=getContext();
		AForkableCursor<Index<Keyword, ACell>> userCursor =
			SignedCursor.create(cursor.withDefault(Maps.empty()).path(ownerDid),value -> rule.sign(policy,value));
		return configured(new SocialUser(userCursor,ownerDid));
	}

	/** Convenience migration overload mapping an Ed25519 key to its {@code did:key}. */
	public SocialUser user(AccountKey ownerKey) {
		if (ownerKey==null) throw new IllegalArgumentException("Owner key must not be null");
		return user(DID.forKey(ownerKey));
	}

	/**
	 * Creates a forked copy for independent operation.
	 * Changes don't affect the parent until {@link #sync()}, which merges with
	 * {@link #SOCIAL_LATTICE}.
	 *
	 * @return Forked Social instance
	 */
	public Social fork() {
		return configured(new Social(this));
	}

	private Social(Social source) {
		super(source);
	}

	@Override
	public Social withCursor(AForkableCursor<AHashMap<ACell, SignedData<Index<Keyword, ACell>>>> cursor) {
		return configured(new Social(cursor));
	}

	/** Reconciles this component's draft using its domain merge rule. */
	@Override
	protected AHashMap<ACell, SignedData<Index<Keyword, ACell>>> mergePublication(
			AHashMap<ACell, SignedData<Index<Keyword, ACell>>> own,
			AHashMap<ACell, SignedData<Index<Keyword, ACell>>> other) {
		return SOCIAL_LATTICE.merge(getContext(),own,other);
	}
}
