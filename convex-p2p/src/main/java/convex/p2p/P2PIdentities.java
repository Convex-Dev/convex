package convex.p2p;

import java.util.function.BiFunction;

import convex.core.data.ACell;
import convex.core.data.AHashMap;
import convex.core.data.AccountKey;
import convex.core.data.MapEntry;
import convex.core.data.SignedData;
import convex.lattice.OwnedMap;
import convex.lattice.cursor.AForkableCursor;

/**
 * The component at {@code :id}: each P2P user's signed identity record, under the
 * user's key. Incoming records merge by the owner rule. Changes on both sides of a fork
 * or transaction reconcile user by user, so each side keeps what only it changed,
 * removals included, and a user's record changed on both sides merges by the owner
 * rule.
 *
 * <p>The application that merges {@code :id} for a host creates this component; another
 * application on the host views it, for use within its own forks and transactions.</p>
 */
public final class P2PIdentities extends OwnedMap<ACell,ACell> {

	/**
	 * Binds the identities region at the place its holder gives it.
	 *
	 * @param cursor Cursor of the {@code :id} owner map, which may be absent
	 */
	public P2PIdentities(AForkableCursor<AHashMap<ACell,SignedData<ACell>>> cursor) {
		super(P2PLattice.ID_LATTICE,cursor);
	}

	/**
	 * Returns the component at one user's identity slot, wrapped in its signing boundary.
	 *
	 * @param owner User key
	 * @param signer Owner-aware signer, which may serve several keys, or null for a read view
	 * @return Identity component of that user
	 */
	public P2PIdentity identity(AccountKey owner,BiFunction<ACell,ACell,SignedData<ACell>> signer) {
		if (owner==null) throw new IllegalArgumentException("User key must not be null");
		return new P2PIdentity(authoringCursor(owner,value -> (signer==null)?null:signer.apply(owner,value)),owner);
	}

	@Override
	public P2PIdentities rebind(AForkableCursor<AHashMap<ACell,SignedData<ACell>>> cursor) {
		return new P2PIdentities(cursor);
	}

	@Override
	public P2PIdentities fork() {
		return rebind(storage.fork());
	}

	@Override
	protected AHashMap<ACell,SignedData<ACell>> reconcile(AHashMap<ACell,SignedData<ACell>> base,
			AHashMap<ACell,SignedData<ACell>> own,AHashMap<ACell,SignedData<ACell>> other) {
		return reconcileEntries(base,own,other,(owner,was,mine,theirs) -> {
			SignedData<ACell> merged=signingFor(owner).merge(value(mine),value(theirs));
			return (merged==null)?null:MapEntry.create(owner,merged);
		});
	}

	private static SignedData<ACell> value(MapEntry<ACell,SignedData<ACell>> entry) {
		return (entry==null)?null:entry.getValue();
	}
}
