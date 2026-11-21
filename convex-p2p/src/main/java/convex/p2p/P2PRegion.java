package convex.p2p;

import java.util.function.BiFunction;

import convex.core.data.ABlobLike;
import convex.core.data.ACell;
import convex.core.data.AHashMap;
import convex.core.data.AccountKey;
import convex.core.data.Index;
import convex.core.data.Keyword;
import convex.core.data.MapEntry;
import convex.core.data.SignedData;
import convex.core.lang.RT;
import convex.lattice.ALattice;
import convex.lattice.AMapComponent;
import convex.lattice.LatticeComponent;
import convex.lattice.OwnedMap;
import convex.lattice.cursor.AForkableCursor;
import convex.lattice.generic.SignedLattice;

/**
 * The component at {@code :p2p}: the node registry at {@code [:p2p :nodes]}, each node's
 * signed record under its node key. Incoming values merge as the region's lattice
 * declares, taking each node's newest record by the owner rule. Changes on both sides
 * of a fork or transaction reconcile entry by entry and, within the registry, node by
 * node, so each side keeps what only it changed, removals included.
 *
 * <p>The application that merges {@code :p2p} for a host creates its own specialisation
 * of this component, with its retention policy; another application on the host views
 * the region with this one, for use within its own forks and transactions.</p>
 */
public class P2PRegion extends AMapComponent<Keyword,ACell,Index<Keyword,ACell>> {

	/**
	 * Binds the region at the place its holder gives it.
	 *
	 * @param cursor Cursor of the {@code :p2p} index, which may be absent
	 */
	public P2PRegion(AForkableCursor<Index<Keyword,ACell>> cursor) {
		super(cursor);
	}

	/**
	 * Returns the component at one node's record, wrapped in its signing boundary.
	 *
	 * @param owner Node key
	 * @param signer Owner-aware signer, which may serve several keys, or null for a read view
	 * @return Record component of that node
	 */
	public P2PNodeRecord node(AccountKey owner,BiFunction<ACell,ACell,SignedData<ACell>> signer) {
		if (owner==null) throw new IllegalArgumentException("User key must not be null");
		return new NodeRecords(entry(P2PLattice.KEY_NODES)).node(owner,signer);
	}

	/** Merges an incoming region as its lattice declares; a value that is not an index changes nothing. */
	@SuppressWarnings("unchecked")
	@Override
	public Index<Keyword,ACell> merge(ACell incoming) {
		if (incoming!=null && !(incoming instanceof Index)) return cursor().getOrDefault(null);
		Index<Keyword,ACell> region=(Index<Keyword,ACell>)incoming;
		return cursor().updateAndGet(own -> P2PLattice.P2P_LATTICE.merge(own,region));
	}

	@Override
	protected Index<Keyword,ACell> mergeAtPath(ACell value,ACell... path) {
		Index<Keyword,ACell> reduced=LatticeComponent.reduce(P2PLattice.P2P_LATTICE,value,path);
		return (reduced==null)?cursor().getOrDefault(null):merge(reduced);
	}

	@Override
	public P2PRegion rebind(AForkableCursor<Index<Keyword,ACell>> cursor) {
		return new P2PRegion(cursor);
	}

	@Override
	public P2PRegion fork() {
		return rebind(storage.fork());
	}

	@Override
	protected Index<Keyword,ACell> reconcile(Index<Keyword,ACell> base,Index<Keyword,ACell> own,Index<Keyword,ACell> other) {
		return reconcileEntries(base,own,other,(key,was,mine,theirs) -> {
			if (isNodes(key)) return MapEntry.create(key,reconcileNodes(nodes(was),nodes(mine),nodes(theirs)));
			// Other entries of the region join as its lattice declares
			ALattice<ACell> lattice=P2PLattice.P2P_LATTICE.path(key);
			return (lattice==null)?theirs:MapEntry.create(key,lattice.merge(value(mine),value(theirs)));
		});
	}

	private static AHashMap<ACell,SignedData<ACell>> reconcileNodes(AHashMap<ACell,SignedData<ACell>> base,
			AHashMap<ACell,SignedData<ACell>> own,AHashMap<ACell,SignedData<ACell>> other) {
		return reconcileEntries(base,own,other,(node,was,mine,theirs) -> {
			SignedLattice<ACell> rule=P2PLattice.NODES_LATTICE.signingFor(node);
			SignedData<ACell> merged=(rule==null)?null:rule.merge(value(mine),value(theirs));
			return (merged==null)?null:MapEntry.create(node,merged);
		});
	}

	/** Whether a stored key is {@code :nodes}, by the index's blob comparison. */
	private static boolean isNodes(ACell key) {
		return (key instanceof ABlobLike<?> blob) && blob.toBlob().equals(P2PLattice.KEY_NODES.toBlob());
	}

	@SuppressWarnings("unchecked")
	private static AHashMap<ACell,SignedData<ACell>> nodes(MapEntry<Keyword,ACell> entry) {
		return (entry!=null && entry.getValue() instanceof AHashMap<?,?> map)?(AHashMap<ACell,SignedData<ACell>>)map:null;
	}

	private static <V extends ACell> V value(MapEntry<?,V> entry) {
		return (entry==null)?null:entry.getValue();
	}

	@Override
	protected Index<Keyword,ACell> createContainer() {
		return Index.none();
	}

	@Override
	protected Index<Keyword,ACell> requireMap(ACell value) {
		if (value==null) return null;
		Index<Keyword,ACell> index=RT.ensureIndex(value);
		if (index==null) throw new IllegalStateException("The P2P region requires an index");
		return index;
	}

	/** The node records at {@code [:p2p :nodes]}, each under its node's key. */
	private static final class NodeRecords extends OwnedMap<ACell,ACell> {
		NodeRecords(AForkableCursor<AHashMap<ACell,SignedData<ACell>>> cursor) {
			super(P2PLattice.NODES_LATTICE,cursor);
		}

		P2PNodeRecord node(AccountKey owner,BiFunction<ACell,ACell,SignedData<ACell>> signer) {
			return new P2PNodeRecord(authoringCursor(owner,value -> (signer==null)?null:signer.apply(owner,value)),owner);
		}
	}
}
