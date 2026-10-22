package convex.social;

import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.Supplier;

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
import convex.core.data.prim.CVMLong;
import convex.core.util.Utils;
import convex.lattice.ALatticeApplication;
import convex.lattice.ALatticeComponent;
import convex.lattice.TransactionalComponent;
import convex.lattice.cursor.AForkableCursor;
import convex.lattice.cursor.Cursors;
import convex.lattice.cursor.SignedCursor;
import convex.lattice.generic.OwnerMapLattice;

/**
 * Cursor-based application layer for the Convex social network.
 *
 * <p>{@code Social} wraps a cursor at the {@code :social} level (an
 * {@link OwnerMapLattice} mapping canonical DIDs to signed per-user state) and
 * provides domain-specific accessors for users, feeds, and follows. The cursor
 * knows nothing of the lattice. The component binds its DID authoriser once and
 * uses that rule for both incoming envelopes and local signatures.</p>
 *
 * <p>An owner-aware signer can serve several DIDs with different keys, returning
 * null for unavailable owners. Without a signer the component is a relay. The
 * clock supplies mutation timestamps; ordering comes from the merged values.
 * Forks and transaction views retain these explicit policies. Inherited contexts
 * neither grant authority nor change the clock.</p>
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
 * forked.user(myDid).feed().post("Post 1");
 * forked.user(myDid).feed().post("Post 2");
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
	 * The social lattice: OwnerMapLattice mapping canonical DIDs to signed per-user state.
	 *
	 * <p>Each owner's value is wrapped in {@code SignedData}; its signer must be an
	 * Ed25519 key authorised for the DID. The inner value is a {@link SocialLattice}
	 * containing {@code :feed}, {@code :profile}, and {@code :following}.</p>
	 */
	public static final OwnerMapLattice<ACell,Index<Keyword,ACell>> SOCIAL_LATTICE=
		ownerLattice(DIDKeyAuthorizer.CONVEX);

	private final OwnerMapLattice<ACell,Index<Keyword,ACell>> owners;
	private final BiFunction<ACell,Index<Keyword,ACell>,SignedData<Index<Keyword,ACell>>> signer;
	private final Supplier<CVMLong> clock;

	/** Binds canonical DID verification for both incoming merge and local signing. */
	public static OwnerMapLattice<ACell,Index<Keyword,ACell>> ownerLattice(DIDKeyAuthorizer authoriser) {
		Objects.requireNonNull(authoriser);
		return OwnerMapLattice.create(SocialLattice.INSTANCE,authoriser::verifiesOwner);
	}

	private Social(AForkableCursor<AHashMap<ACell,SignedData<Index<Keyword,ACell>>>> cursor,
			OwnerMapLattice<ACell,Index<Keyword,ACell>> owners,
			BiFunction<ACell,Index<Keyword,ACell>,SignedData<Index<Keyword,ACell>>> signer,
			Supplier<CVMLong> clock) {
		super(cursor);
		this.owners=owners;
		this.signer=signer;
		this.clock=Objects.requireNonNull(clock);
	}

	/** Creates a standalone did:key author with wall-clock mutation timestamps. */
	public static Social create(AKeyPair keyPair) {
		return create((owner,value) -> keyPair.signData(value),Utils::getCurrentTimestampCell);
	}

	/** Creates a multi-owner did:key author, or a relay with a null signer. */
	public static Social create(BiFunction<ACell,Index<Keyword,ACell>,SignedData<Index<Keyword,ACell>>> signer,
			Supplier<CVMLong> clock) {
		return create(DIDKeyAuthorizer.CONVEX,signer,clock);
	}

	/** Creates standalone state with explicit DID resolution, multi-owner signing and clock. */
	public static Social create(DIDKeyAuthorizer authoriser,
			BiFunction<ACell,Index<Keyword,ACell>,SignedData<Index<Keyword,ACell>>> signer,
			Supplier<CVMLong> clock) {
		return connect(Cursors.root(Maps.empty()),authoriser,signer,clock);
	}

	/** Connects a did:key relay at the social owner-map cursor. */
	public static Social connect(AForkableCursor<AHashMap<ACell,SignedData<Index<Keyword,ACell>>>> cursor) {
		return connect(cursor,DIDKeyAuthorizer.CONVEX,null,Utils::getCurrentTimestampCell);
	}

	/**
	 * Connects explicit policy shared by all users and derived views. One signer
	 * may serve many DIDs using different keys, returning null for unavailable
	 * owners. Its results must pass the bound authoriser. Callbacks must support
	 * concurrent use and atomic retries; refresh external authority outside them.
	 */
	public static Social connect(AForkableCursor<AHashMap<ACell,SignedData<Index<Keyword,ACell>>>> cursor,
			DIDKeyAuthorizer authoriser,
			BiFunction<ACell,Index<Keyword,ACell>,SignedData<Index<Keyword,ACell>>> signer,
			Supplier<CVMLong> clock) {
		return new Social(cursor,ownerLattice(authoriser),signer,clock);
	}

	/** Opens a relay view of an application's standard social region. */
	public static Social connect(ALatticeApplication<Index<Keyword,ACell>> application) {
		return connect(application.cursor().withDefault(Index.none()).path(KEY_SOCIAL));
	}

	/** Opens a did:key author view of an application's standard social region. */
	public static Social connect(ALatticeApplication<Index<Keyword,ACell>> application,AKeyPair keyPair) {
		return connect(application.cursor().withDefault(Index.none()).path(KEY_SOCIAL),
			DIDKeyAuthorizer.CONVEX,(owner,value) -> keyPair.signData(value),Utils::getCurrentTimestampCell);
	}

	/**
	 * Gets a user view, wrapping the owner's slot in its signing boundary.
	 *
	 * <p>This component owns the owner-keyed level, so it is the one place that
	 * crosses into a user's signed value. The returned {@link SocialUser} wraps a
	 * cursor at the per-user {@link SocialLattice} level; writes through it are
	 * signed with the explicit signer for {@code ownerDid}, under the same bound
	 * authorisation rule as incoming merges. No primary identity is required.</p>
	 *
	 * @param ownerDid the user's canonical base DID
	 * @return SocialUser for the specified owner
	 */
	public SocialUser user(convex.core.data.AString ownerDid) {
		if (!DID.isCanonicalBase(ownerDid)) {
			throw new IllegalArgumentException("Social owner must be a canonical base DID");
		}
		var rule=owners.signingFor(ownerDid);
		AForkableCursor<Index<Keyword, ACell>> userCursor =
			SignedCursor.create(cursor().withDefault(Maps.empty()).path(ownerDid),value -> signer==null?null:rule.sign(value,v -> signer.apply(ownerDid,v)));
		return new SocialUser(userCursor,ownerDid,clock);
	}

	/** Convenience migration overload mapping an Ed25519 key to its {@code did:key}. */
	public SocialUser user(AccountKey ownerKey) {
		if (ownerKey==null) throw new IllegalArgumentException("Owner key must not be null");
		return user(DID.forKey(ownerKey));
	}

	/**
	 * Creates a forked copy for independent operation.
	 * Changes don't affect the parent until {@link #sync()}, which reconciles with
	 * the same owner rule and signer.
	 *
	 * @return Forked Social instance
	 */
	public Social fork() {
		return new Social(this);
	}

	private Social(Social source) {
		super(source);
		owners=source.owners;
		signer=source.signer;
		clock=source.clock;
	}

	@Override
	public Social withCursor(AForkableCursor<AHashMap<ACell, SignedData<Index<Keyword, ACell>>>> cursor) {
		return new Social(cursor,owners,signer,clock);
	}

	/** Merges incoming owner envelopes into this region's working state. */
	@Override
	public AHashMap<ACell,SignedData<Index<Keyword,ACell>>> merge(
			AHashMap<ACell,SignedData<Index<Keyword,ACell>>> incoming) {
		return cursor().updateAndGet(own -> mergePublication(own,incoming));
	}

	/** Accepts whole owner envelopes, never unsigned edits inside them. */
	@SuppressWarnings("unchecked")
	@Override
	public AHashMap<ACell,SignedData<Index<Keyword,ACell>>> mergeAt(
			ACell value,ACell... path) {
		if (path.length==0) {
			return value instanceof AHashMap<?,?>?merge(
				(AHashMap<ACell,SignedData<Index<Keyword,ACell>>>)value):cursor().get();
		}
		if (path.length!=1 || !(value instanceof SignedData<?>)) return cursor().get();
		return merge(Maps.of(path[0],value));
	}

	/** Reconciles this component's draft using its domain merge rule. */
	@Override
	protected AHashMap<ACell, SignedData<Index<Keyword, ACell>>> mergePublication(
			AHashMap<ACell, SignedData<Index<Keyword, ACell>>> own,
			AHashMap<ACell, SignedData<Index<Keyword, ACell>>> other) {
		return signer==null?owners.merge(own,other):owners.mergeAndSign(own,other,signer);
	}
}
