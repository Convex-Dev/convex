package convex.p2p;

import convex.core.data.ACell;
import convex.core.data.AHashMap;
import convex.core.data.AccountKey;
import convex.core.data.Index;
import convex.core.data.Keyword;
import convex.core.data.SignedData;
import convex.lattice.ALattice;
import convex.lattice.ALatticeApplication;
import convex.lattice.LatticeContext;
import convex.lattice.LatticeHost;
import convex.lattice.cursor.AForkableCursor;
import convex.lattice.cursor.SignedCursor;

/**
 * Root-level application component for the P2P lattice regions.
 *
 * <p>The application composes domain components over a generic hosted root and
 * has no dependency on {@link convex.node.NodeServer}. A {@link P2PNode} is one
 * possible bootstrap and lifecycle owner; local applications may use the same
 * component over a standalone {@link LatticeHost}.</p>
 *
 * <p>This component owns the P2P regions, so it declares their shapes from
 * {@link P2PLattice} when navigating and crosses each owner's slot with the owner
 * lattice that verifies it.</p>
 */
public class P2PApplication extends ALatticeApplication<Index<Keyword,ACell>> {

	protected P2PApplication(ALattice<Index<Keyword,ACell>> lattice,LatticeHost<Index<Keyword,ACell>> host) {
		// NodeServer restores after this adapter is constructed. Until node lifecycle
		// policy explicitly stages and refreshes applications, use a live host view.
		super(lattice,host.cursor());
		setContext(host.getContext());
	}

	/** Binds a working view, such as a fork or transaction draft, to the supplied cursor. */
	protected P2PApplication(ALattice<Index<Keyword,ACell>> lattice, AForkableCursor<Index<Keyword,ACell>> cursor) {
		super(lattice,cursor);
	}

	/** Connects a P2P application to a hosted lattice root. */
	public static P2PApplication connect(ALattice<Index<Keyword,ACell>> lattice,LatticeHost<Index<Keyword,ACell>> host) {
		return new P2PApplication(lattice,host);
	}

	/** Returns the component representing one P2P user's owned regions. */
	public P2PUser p2p(AccountKey userKey) {
		return P2PUser.create(this,userKey);
	}

	/** Returns the component at one user's identity slot, wrapped in its signing boundary. */
	public P2PIdentity identity(AccountKey userKey) {
		if (userKey==null) throw new IllegalArgumentException("User key must not be null");
		return configured(new P2PIdentity(identitySlot(this,userKey),userKey));
	}

	/** Returns the component at one user's node-record slot, wrapped in its signing boundary. */
	public P2PNodeRecord node(AccountKey userKey) {
		if (userKey==null) throw new IllegalArgumentException("User key must not be null");
		return configured(new P2PNodeRecord(nodeSlot(this,userKey),userKey));
	}

	/**
	 * Cursor at {@code [:id owner :value]}. The application navigates to the
	 * {@code :id} level with its own root lattice; this component owns that level,
	 * so it wraps the owner's slot in the signing boundary bound to the
	 * application's policy.
	 */
	static AForkableCursor<ACell> identitySlot(ALatticeApplication<?> application, AccountKey userKey) {
		AForkableCursor<AHashMap<ACell,SignedData<ACell>>> ids=application.resolve(P2PLattice.KEY_ID);
		var rule=P2PLattice.ID_LATTICE.signingFor(userKey);
		LatticeContext context=application.getContext();
		return SignedCursor.create(resolve(ids,P2PLattice.ID_LATTICE,userKey),v -> rule.sign(context,v));
	}

	/** Cursor at {@code [:p2p :nodes owner :value]}, navigated and signed the same way. */
	static AForkableCursor<ACell> nodeSlot(ALatticeApplication<?> application, AccountKey userKey) {
		AForkableCursor<AHashMap<ACell,SignedData<ACell>>> nodes=
			application.resolve(P2PLattice.KEY_P2P,P2PLattice.KEY_NODES);
		var rule=P2PLattice.NODES_LATTICE.signingFor(userKey);
		LatticeContext context=application.getContext();
		return SignedCursor.create(resolve(nodes,P2PLattice.NODES_LATTICE,userKey),v -> rule.sign(context,v));
	}
}
