package convex.p2p;

import convex.core.data.ACell;
import convex.core.data.AHashMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.AccountKey;
import convex.core.data.Keyword;
import convex.lattice.ALatticeComponent;
import convex.lattice.MergeConflictException;
import convex.lattice.TransactionalComponent;
import convex.lattice.cursor.AForkableCursor;

/** Component at one user's {@code [:id owner :value]} lattice path. */
public final class P2PIdentity extends ALatticeComponent<ACell> implements TransactionalComponent<ACell,P2PIdentity> {

	private final AccountKey owner;

	P2PIdentity(AForkableCursor<ACell> cursor, AccountKey owner) {
		super(cursor);
		this.owner=owner;
	}

	/** Returns the owner of this identity slot. */
	public AccountKey owner() {
		return owner;
	}

	/** Returns the published identity record, or {@code null}. */
	@SuppressWarnings("unchecked")
	public AHashMap<Keyword,ACell> getIdentity() {
		return (AHashMap<Keyword,ACell>) cursor().getOrDefault(null);
	}

	/** Replaces the identity record in this component's working cursor. */
	public void setIdentity(AHashMap<Keyword,ACell> identity) {
		cursor().set(identity);
	}

	/** Publishes name and operated-node keys with an explicit timestamp. */
	public void setIdentity(AString name, AVector<ACell> nodes, long timestamp) {
		setIdentity(P2PLattice.createIdentity(name,nodes,null,timestamp));
	}

	/**
	 * Creates an isolated working component at the same logical path. Its sync fails
	 * with a {@link MergeConflictException}, changing nothing, if the record here has
	 * changed too.
	 */
	public P2PIdentity fork() {
		return rebind(storage.fork());
	}

	@Override
	public P2PIdentity rebind(AForkableCursor<ACell> cursor) {
		return new P2PIdentity(cursor,owner);
	}
}
