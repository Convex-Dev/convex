package convex.p2p;

import convex.core.data.ACell;
import convex.core.data.AHashMap;
import convex.core.data.AccountKey;
import convex.core.data.Keyword;
import convex.lattice.ALatticeComponent;
import convex.lattice.MergeConflictException;
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
		return (AHashMap<Keyword,ACell>) cursor().getOrDefault(null);
	}

	/** Replaces the node information in this component's working cursor. */
	public void setNodeInfo(AHashMap<Keyword,ACell> nodeInfo) {
		cursor().set(nodeInfo);
	}

	/**
	 * Creates an isolated working component at the same logical path. Its sync fails
	 * with a {@link MergeConflictException}, changing nothing, if the record here has
	 * changed too.
	 */
	public P2PNodeRecord fork() {
		return rebind(storage.fork());
	}

	@Override
	public P2PNodeRecord rebind(AForkableCursor<ACell> cursor) {
		return new P2PNodeRecord(cursor,owner);
	}
}
