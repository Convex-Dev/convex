package convex.p2p;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;
import java.util.function.Supplier;

import convex.core.data.ACell;
import convex.core.data.AHashMap;
import convex.core.data.AccountKey;
import convex.core.data.Blob;
import convex.core.data.Hash;
import convex.core.data.Index;
import convex.core.data.Keyword;
import convex.core.data.Maps;
import convex.core.data.SignedData;
import convex.core.lang.RT;
import convex.lattice.ALatticeApplication;

/**
 * Connection management for the P2P application: the node registry this node holds
 * at {@code [:p2p :nodes]}, its validated view as a {@link NodeDirectory}, and what
 * this node has learned about other nodes as {@link ContactRecords}.
 *
 * <p>It owns the merge of the {@code :p2p} region. An incoming region merges as the
 * lattice declares, verifying each owner's record and taking the newer of two, and
 * the registry is then reduced to the records this node keeps:</p>
 * <ul>
 *   <li>always, and not counted: its own record and those of nodes the operator
 *       named, nodes it has an authenticated route to or is authenticating one from,
 *       and its Points of Presence;</li>
 *   <li>always: records it held of nodes that answered and have not failed since;</li>
 *   <li>with those, up to the bound: the best of the rest, nodes that answered first,
 *       then nodes never reached, then nodes that failed, each in an order salted
 *       with a secret drawn when this component is created.</li>
 * </ul>
 *
 * <p>Except its own, a record is kept only if it is a valid NodeInfo within
 * {@link P2PLattice#MAX_NODE_RECORD_SIZE}; a newer record that is not leaves the one
 * held. The salted order is random to other nodes, so none can choose which records
 * this node keeps, and stable while it runs, so a record turned away is turned away
 * again and exchanges between nodes settle. Evidence comes from this node's contact
 * records, never from a record itself. A dropped record leaves the directory but not
 * the connection manager's candidates.</p>
 */
final class P2PConnections extends ALatticeApplication<Index<Keyword,ACell>> {

	/** Bound on counted records until a node supplies its own. */
	static final int DEFAULT_MAX_RECORDS=256;

	private final AccountKey ownKey;
	private final NodeDirectory directory;
	private final ContactRecords contacts;
	private final Set<AccountKey> named=ConcurrentHashMap.newKeySet();
	private final Blob salt=Blob.createRandom(new SecureRandom(),32);
	private volatile Supplier<Set<AccountKey>> routes=() -> Set.of();
	private volatile int maxRecords=DEFAULT_MAX_RECORDS;

	@SuppressWarnings("unchecked")
	P2PConnections(P2PApplication application,BiFunction<ACell,ACell,SignedData<ACell>> signer) {
		super(application.cursor());
		this.ownKey=application.ownKey();
		this.directory=new NodeDirectory(application);
		// Only a node that can sign its own :local slot keeps contact records
		this.contacts=(ownKey==null || signer==null)?null:new ContactRecords(cursor(),ownKey,
			value -> (SignedData<AHashMap<Keyword,ACell>>)(SignedData<?>)signer.apply(ownKey,value),application.clock());
	}

	/** This node's validated view of the registry it holds. */
	NodeDirectory directory() {
		return directory;
	}

	/** This node's contact records, or null for a node without a key. */
	ContactRecords contacts() {
		return contacts;
	}

	/** Bounds the counted records this node keeps; a node sets this to its candidate bound. */
	void setMaxRecords(int maxRecords) {
		if (maxRecords<0) throw new IllegalArgumentException("Record bound must not be negative");
		this.maxRecords=maxRecords;
	}

	/** Supplies the nodes this node has, or is authenticating, a route to. */
	void setRoutes(Supplier<Set<AccountKey>> routes) {
		this.routes=routes;
	}

	/** Keeps a node's record, as the operator named it. */
	void name(AccountKey node) {
		named.add(node);
	}

	/** Syncs this node's contact records into its {@code :local} slot. */
	@Override
	protected void syncChildren() {
		if (contacts!=null) contacts.sync();
	}

	/**
	 * Merges the {@code :p2p} region of an incoming root and keeps the records this
	 * node wants. Other regions are left to the application.
	 */
	@Override
	@SuppressWarnings("unchecked")
	public Index<Keyword,ACell> merge(Index<Keyword,ACell> incoming) {
		if (incoming==null || !(incoming.get(P2PLattice.KEY_P2P) instanceof Index<?,?> region)) return cursor().get();
		Index<Keyword,ACell> other=(Index<Keyword,ACell>)region;
		// Taken before the update, which may run more than once
		Set<AccountKey> pinned=pinned();
		AHashMap<AccountKey,AHashMap<Keyword,ACell>> evidence=(contacts==null)?Maps.empty():contacts.all();
		return cursor().updateAndGet(own -> {
			Index<Keyword,ACell> held=(Index<Keyword,ACell>)own.get(P2PLattice.KEY_P2P);
			Index<Keyword,ACell> merged=P2PLattice.P2P_LATTICE.merge(held,other);
			if (merged==held) return own;
			var kept=retain(nodes(held),nodes(merged),pinned,evidence);
			Index<Keyword,ACell> updated=merged.assoc(P2PLattice.KEY_NODES,kept);
			// A registry that turns away everything new is left as it was
			return updated.equals(held)?own:own.assoc(P2PLattice.KEY_P2P,updated);
		});
	}

	/** Reduces the held records, with newer and new ones merged in, to those this node keeps. */
	private AHashMap<ACell,SignedData<ACell>> retain(AHashMap<ACell,SignedData<ACell>> held,
			AHashMap<ACell,SignedData<ACell>> offered,Set<AccountKey> pinned,
			AHashMap<AccountKey,AHashMap<Keyword,ACell>> evidence) {
		AHashMap<ACell,SignedData<ACell>> kept=Maps.empty();
		List<Candidate> candidates=new ArrayList<>();
		int room=maxRecords;
		for (Map.Entry<ACell,SignedData<ACell>> entry:offered.entrySet()) {
			AccountKey node=RT.ensureAccountKey(entry.getKey());
			if (node==null) continue;
			SignedData<ACell> current=held.get(node);
			SignedData<ACell> record=wanted(node,entry.getValue())?entry.getValue():current;
			if (!wanted(node,record)) continue;
			AHashMap<Keyword,ACell> contact=evidence.get(node);
			if (pinned.contains(node)) {
				kept=kept.assoc(node,record);
			} else if (current!=null && ContactRecords.answered(contact)) {
				kept=kept.assoc(node,record);
				room--;
			} else {
				candidates.add(new Candidate(node,record,standing(contact),salt.append(node).getContentHash()));
			}
		}
		candidates.sort(null);
		for (int i=0; i<Math.min(room,candidates.size()); i++) {
			kept=kept.assoc(candidates.get(i).node(),candidates.get(i).record());
		}
		return kept;
	}

	/** Whether this node keeps a record: its own, or another's valid NodeInfo within the size limit. */
	private boolean wanted(AccountKey node,SignedData<ACell> record) {
		if (record==null) return false;
		if (node.equals(ownKey)) return true;
		return ACell.getMemorySize(record)<=P2PLattice.MAX_NODE_RECORD_SIZE && NodeDirectory.isValid(node,record);
	}

	/** Nodes whose records are kept and not counted. */
	private Set<AccountKey> pinned() {
		Set<AccountKey> pinned=new HashSet<>(named);
		pinned.addAll(routes.get());
		for (AccountKey pop:directory.pointsOfPresence()) pinned.add(pop);
		if (ownKey!=null) pinned.add(ownKey);
		return pinned;
	}

	/** Orders candidates: answered, then never reached, then failed. */
	private static int standing(AHashMap<Keyword,ACell> contact) {
		if (ContactRecords.answered(contact)) return 0;
		return ContactRecords.failing(contact)?2:1;
	}

	@SuppressWarnings("unchecked")
	private static AHashMap<ACell,SignedData<ACell>> nodes(Index<Keyword,ACell> region) {
		ACell nodes=(region==null)?null:region.get(P2PLattice.KEY_NODES);
		return (nodes instanceof AHashMap<?,?> map)?(AHashMap<ACell,SignedData<ACell>>)map:Maps.empty();
	}

	/** A record competing for the counted places, by standing then salted rank. */
	private record Candidate(AccountKey node,SignedData<ACell> record,int standing,Hash rank) implements Comparable<Candidate> {
		@Override
		public int compareTo(Candidate other) {
			int c=Integer.compare(standing,other.standing);
			return (c!=0)?c:rank.compareTo(other.rank);
		}
	}
}
