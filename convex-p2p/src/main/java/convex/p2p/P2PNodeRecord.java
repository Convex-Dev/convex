package convex.p2p;

import convex.core.data.ACell;
import convex.core.data.AHashMap;
import convex.core.data.AccountKey;
import convex.core.data.Keyword;
import convex.lattice.ALatticeComponent;
import convex.lattice.cursor.ALatticeCursor;

/** Component at one user's {@code [:p2p :nodes owner :value]} lattice path. */
public final class P2PNodeRecord extends ALatticeComponent<ACell> {

	private final AccountKey owner;

	P2PNodeRecord(ALatticeComponent<?> parent, ALatticeCursor<ACell> cursor,
			AccountKey owner) {
		super(parent,cursor);
		this.owner=owner;
	}

	/** Returns the owner of this node record. */
	public AccountKey owner() {
		return owner;
	}

	/** Returns the published node information, or {@code null}. */
	@SuppressWarnings("unchecked")
	public AHashMap<Keyword,ACell> getNodeInfo() {
		return (AHashMap<Keyword,ACell>) cursor.get();
	}

	/** Replaces the node information in this component's working cursor. */
	public void setNodeInfo(AHashMap<Keyword,ACell> nodeInfo) {
		cursor.set(nodeInfo);
	}

	/** Creates an isolated working component at the same logical path. */
	public P2PNodeRecord fork() {
		return new P2PNodeRecord(parent(),cursor.fork(),owner);
	}
}
