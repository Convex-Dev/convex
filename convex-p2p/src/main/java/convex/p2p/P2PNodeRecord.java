package convex.p2p;

import convex.core.data.ACell;
import convex.core.data.AHashMap;
import convex.core.data.AccountKey;
import convex.core.data.Keyword;
import convex.lattice.ALatticeComponent;
import convex.lattice.TransactionalComponent;
import convex.lattice.cursor.AForkableCursor;

/** Component at one user's {@code [:p2p :nodes owner :value]} lattice path. */
public final class P2PNodeRecord extends ALatticeComponent<ACell> implements TransactionalComponent<ACell,P2PNodeRecord> {

	private final AccountKey owner;

	P2PNodeRecord(AForkableCursor<ACell> cursor, AccountKey owner) {
		super(cursor);
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

	/** Creates an isolated working component at the same logical path, merging on sync with the node value lattice. */
	public P2PNodeRecord fork() {
		return configured(new P2PNodeRecord(this));
	}

	private P2PNodeRecord(P2PNodeRecord source) {
		super(P2PLattice.NODES_LATTICE.getValueLattice(),source.cursor);
		this.owner=source.owner;
	}
	@Override
	public P2PNodeRecord withCursor(AForkableCursor<ACell> cursor) {
		return configured(new P2PNodeRecord(cursor,owner));
	}

}
