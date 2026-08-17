package convex.p2p;

import convex.core.cvm.Keywords;
import convex.core.data.ACell;
import convex.core.data.AccountKey;
import convex.core.data.Index;
import convex.core.data.Keyword;
import convex.lattice.ALatticeApplication;
import convex.lattice.RootComponent;

/**
 * Root-level application component for the P2P lattice regions.
 *
 * <p>The application composes domain components over a generic hosted root and
 * has no dependency on {@link convex.node.NodeServer}. A {@link P2PNode} is one
 * possible bootstrap and lifecycle owner; local applications may use the same
 * component over a standalone {@link RootComponent}.</p>
 */
public class P2PApplication extends ALatticeApplication<Index<Keyword,ACell>> {

	protected P2PApplication(RootComponent<Index<Keyword,ACell>> host) {
		super(host);
	}

	/** Connects a P2P application to a hosted lattice root. */
	public static P2PApplication connect(RootComponent<Index<Keyword,ACell>> host) {
		return new P2PApplication(host);
	}

	/** Returns the component representing one P2P user's owned regions. */
	public P2PUser p2p(AccountKey userKey) {
		return P2PUser.create(this,userKey);
	}

	/** Returns the component at one user's identity path. */
	public P2PIdentity identity(AccountKey userKey) {
		if (userKey==null) throw new IllegalArgumentException("User key must not be null");
		return new P2PIdentity(this,
			cursor.path(P2PLattice.KEY_ID,userKey,Keywords.VALUE),userKey);
	}

	/** Returns the component at one user's node-record path. */
	public P2PNodeRecord node(AccountKey userKey) {
		if (userKey==null) throw new IllegalArgumentException("User key must not be null");
		return new P2PNodeRecord(this,
			cursor.path(P2PLattice.KEY_P2P,P2PLattice.KEY_NODES,userKey,Keywords.VALUE),
			userKey);
	}
}
