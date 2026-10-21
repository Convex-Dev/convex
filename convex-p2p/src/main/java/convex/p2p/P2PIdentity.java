package convex.p2p;

import convex.core.data.ACell;
import convex.core.data.AHashMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.AccountKey;
import convex.core.data.Keyword;
import convex.lattice.ALatticeComponent;
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
		return (AHashMap<Keyword,ACell>) cursor.get();
	}

	/** Replaces the identity record in this component's working cursor. */
	public void setIdentity(AHashMap<Keyword,ACell> identity) {
		cursor.set(identity);
	}

	/** Publishes name and operated-node keys with an explicit timestamp. */
	public void setIdentity(AString name, AVector<ACell> nodes, long timestamp) {
		setIdentity(P2PLattice.createIdentity(name,nodes,null,timestamp));
	}

	/** Creates an isolated working component at the same logical path, merging on sync with the identity value lattice. */
	public P2PIdentity fork() {
		return configured(new P2PIdentity(this));
	}

	private P2PIdentity(P2PIdentity source) {
		super(source);
		this.owner=source.owner;
	}
	@Override
	public P2PIdentity withCursor(AForkableCursor<ACell> cursor) {
		return configured(new P2PIdentity(cursor,owner));
	}

	/** Reconciles this component's draft using its domain merge rule. */
	@Override
	protected ACell mergePublication(ACell own,ACell other) {
		return P2PLattice.ID_LATTICE.getValueLattice().merge(getContext(),own,other);
	}
}
