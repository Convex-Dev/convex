package convex.p2p;

import convex.core.data.ACell;
import convex.core.data.AHashMap;
import convex.core.data.AccountKey;
import convex.core.data.Index;
import convex.core.data.Keyword;
import convex.core.data.SignedData;
import convex.lattice.ALattice;
import convex.lattice.ALatticeApplication;
import convex.lattice.RootComponent;
import convex.lattice.cursor.AForkableCursor;
import convex.lattice.cursor.SignedCursor;

/**
 * Root-level application component for the P2P lattice regions.
 *
 * <p>The application composes domain components over a generic hosted root and
 * has no dependency on {@link convex.node.NodeServer}. A {@link P2PNode} is one
 * possible bootstrap and lifecycle owner; local applications may use the same
 * component over a standalone {@link RootComponent}.</p>
 *
 * <p>This component owns the P2P regions, so it declares their shapes from
 * {@link P2PLattice} when navigating and crosses each owner's slot with the owner
 * lattice that verifies it.</p>
 */
public class P2PApplication extends ALatticeApplication<Index<Keyword,ACell>> {

	protected P2PApplication(RootComponent<Index<Keyword,ACell>> host) {
		// NodeServer restores after this adapter is constructed. Until node lifecycle
		// policy explicitly stages and refreshes applications, use a live host view.
		super(host.getLattice(),host.cursor().view());
	}

	/** Binds a working view, such as a fork or transaction draft, to the supplied cursor. */
	protected P2PApplication(ALattice<Index<Keyword,ACell>> lattice, AForkableCursor<Index<Keyword,ACell>> cursor) {
		super(lattice,cursor);
	}

	/** Connects a P2P application to a hosted lattice root. */
	public static P2PApplication connect(RootComponent<Index<Keyword,ACell>> host) {
		return new P2PApplication(host);
	}

	/** Returns the component representing one P2P user's owned regions. */
	public P2PUser p2p(AccountKey userKey) {
		return P2PUser.create(cursor,userKey);
	}

	/** Returns the component at one user's identity slot, wrapped in its signing boundary. */
	public P2PIdentity identity(AccountKey userKey) {
		if (userKey==null) throw new IllegalArgumentException("User key must not be null");
		return new P2PIdentity(identitySlot(cursor,userKey),userKey);
	}

	/** Returns the component at one user's node-record slot, wrapped in its signing boundary. */
	public P2PNodeRecord node(AccountKey userKey) {
		if (userKey==null) throw new IllegalArgumentException("User key must not be null");
		return new P2PNodeRecord(nodeSlot(cursor,userKey),userKey);
	}

	/** Cursor at {@code [:id owner :value]}: the {@code :id} level declared, the owner slot signed. */
	static AForkableCursor<ACell> identitySlot(AForkableCursor<?> root, AccountKey userKey) {
		AForkableCursor<AHashMap<ACell,SignedData<ACell>>> ids=root.child(P2PLattice.KEY_ID,P2PLattice.ID_LATTICE);
		return SignedCursor.forOwner(ids.path(userKey),P2PLattice.ID_LATTICE,userKey);
	}

	/** Cursor at {@code [:p2p :nodes owner :value]}: both levels declared, the owner slot signed. */
	static AForkableCursor<ACell> nodeSlot(AForkableCursor<?> root, AccountKey userKey) {
		AForkableCursor<AHashMap<ACell,SignedData<ACell>>> nodes=root
			.child(P2PLattice.KEY_P2P,P2PLattice.P2P_LATTICE)
			.child(P2PLattice.KEY_NODES,P2PLattice.NODES_LATTICE);
		return SignedCursor.forOwner(nodes.path(userKey),P2PLattice.NODES_LATTICE,userKey);
	}
}
