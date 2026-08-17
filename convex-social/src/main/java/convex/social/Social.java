package convex.social;

import convex.core.crypto.AKeyPair;
import convex.core.data.ACell;
import convex.core.data.AccountKey;
import convex.core.data.AHashMap;
import convex.core.data.Index;
import convex.core.data.Keyword;
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
 * (an {@link OwnerLattice} mapping owner keys to signed per-user state)
 * and provides domain-specific accessors for users, feeds, and follows.</p>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * // Standalone
 * Social social = Social.create(myKeyPair);
 * social.user(myKeyPair.getAccountKey()).feed().post("Hello!");
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
	 * The social lattice: OwnerLattice mapping owner keys to signed per-user state.
	 *
	 * <p>Each owner's value is wrapped in {@code SignedData} — only the owner's
	 * Ed25519 key can sign updates. The inner value is a {@link SocialLattice}
	 * containing :feed, :profile, and :follows.</p>
	 */
	public static final OwnerLattice<Index<Keyword, ACell>> SOCIAL_LATTICE =
		OwnerLattice.create(SocialLattice.INSTANCE);

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
		LatticeContext ctx = LatticeContext.create(null, keyPair);
		ALatticeCursor<AHashMap<ACell, SignedData<Index<Keyword, ACell>>>> cursor =
			Cursors.createLattice(SOCIAL_LATTICE);
		cursor.withContext(ctx);
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
		LatticeContext ctx = LatticeContext.create(null, keyPair);
		ALatticeCursor<AHashMap<ACell, SignedData<Index<Keyword, ACell>>>> socialCursor =
			rootCursor.path(KEY_SOCIAL);
		socialCursor.withContext(ctx);
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
		Social social=connect(parent);
		social.cursor.setContext(LatticeContext.create(null,keyPair));
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
	 * {@link SocialLattice} level. Writes are automatically signed using
	 * the key pair from the context.</p>
	 *
	 * @param ownerKey The user's account key (Ed25519 public key)
	 * @return SocialUser for the specified owner
	 */
	public SocialUser user(AccountKey ownerKey) {
		ALatticeCursor<Index<Keyword, ACell>> userCursor =
			cursor.path(ownerKey, Keywords.VALUE);
		return new SocialUser(this,userCursor,ownerKey);
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
