package convex.p2p;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.function.ToIntFunction;

import convex.core.data.ACell;
import convex.core.data.AHashMap;
import convex.core.data.AccountKey;
import convex.core.data.Blob;
import convex.core.data.Cells;
import convex.core.data.Hash;
import convex.core.data.Index;
import convex.core.data.Keyword;
import convex.core.data.Maps;
import convex.core.data.SignedData;
import convex.core.lang.RT;
import convex.core.util.MergeFunction;
import convex.lattice.ALatticeComponent;
import convex.lattice.cursor.AForkableCursor;

/**
 * The node registry this node holds at {@code [:p2p :nodes]}, as the component at
 * {@code [:p2p]}: it defines how that region merges.
 *
 * <p>Its merge is the registry's acceptance boundary: the owner rule, applied only to
 * the records this node wants. Nodes that accept every record converge under the
 * owner rule, which takes each owner's newest signed record; this node holds a subset
 * of that. An incoming record that differs from the one held is checked first,
 * cheapest check first, and merged only if it passes. The checks, the pins and the
 * ranking are those of retention in {@code P2P_DESIGN.md} §4.2. The region's other
 * entries merge as the lattice declares.</p>
 *
 * <p>The registry holds no local state. What it reads is supplied: whether this node
 * refuses a node, each node's standing in this node's own contact records, never in
 * a record itself, and the nodes to pin. Pins are this node's own record and the
 * nodes its node supplies: those the operator named, those it has an authenticated
 * route to or is authenticating one from, and its Points of Presence, all bounded by
 * configuration and connections. The salt is drawn when this component is created, so
 * the order is stable while the node runs.</p>
 *
 * <p>Held records are not checked again: they passed when they were merged. The
 * lowest held rank used to turn new nodes away is worked out once for each registry
 * value, so standings and pins that change in between take effect at the next
 * change. Every node keeps a different subset, so each exchange offers records
 * turned away before, and each costs only a comparison. A dropped record leaves the
 * directory but not the connection manager's candidates.</p>
 */
final class NodeRegistry extends ALatticeComponent<Index<Keyword,ACell>> {

	/** Bound on counted records, the contact-record cap, so each can carry contact history. */
	static final int DEFAULT_MAX_RECORDS=ContactRecords.MAX_NODES;

	private final AccountKey ownKey;
	private final Predicate<AccountKey> refused;
	private final Supplier<ToIntFunction<AccountKey>> standings;
	private final Blob salt=Blob.createRandom(new SecureRandom(),32);
	private volatile Supplier<Set<AccountKey>> pinned=Set::of;
	private volatile int maxRecords=DEFAULT_MAX_RECORDS;
	private volatile Cutoff cutoff;

	/**
	 * Binds the registry at {@code [:p2p]}.
	 *
	 * @param cursor cursor at the {@code :p2p} region
	 * @param ownKey this node's key, whose record is always kept, or null for a keyless node
	 * @param refused whether this node refuses a node
	 * @param standings supplies a snapshot of each node's standing, lower ranking first,
	 *        as {@link ContactRecords#standing} orders them
	 */
	NodeRegistry(AForkableCursor<Index<Keyword,ACell>> cursor,AccountKey ownKey,
			Predicate<AccountKey> refused,Supplier<ToIntFunction<AccountKey>> standings) {
		super(cursor);
		this.ownKey=ownKey;
		this.refused=refused;
		this.standings=standings;
	}

	/** Bounds the counted records this node keeps. */
	void setMaxRecords(int maxRecords) {
		if (maxRecords<0) throw new IllegalArgumentException("Record bound must not be negative");
		this.maxRecords=maxRecords;
		cutoff=null;
	}

	/** Supplies the nodes whose records are kept and not counted, besides this node's own. */
	void setPinned(Supplier<Set<AccountKey>> pinned) {
		this.pinned=pinned;
	}

	/** Drops a node's record, if this node holds one. */
	void drop(AccountKey node) {
		cursor().updateAndGet(held -> {
			AHashMap<ACell,SignedData<ACell>> nodes=nodes(held);
			return nodes.containsKey(node)?held.assoc(P2PLattice.KEY_NODES,nodes.dissoc(node)):held;
		});
	}

	/** Merges an incoming {@code :p2p} region: the registry by this node's rule, any other entries as the lattice declares. */
	@Override
	public Index<Keyword,ACell> merge(Index<Keyword,ACell> incoming) {
		if (incoming==null) return cursor().getOrDefault(null);
		return cursor().updateAndGet(held -> {
			Index<Keyword,ACell> base=(held==null)?P2PLattice.P2P_LATTICE.zero():held;
			Index<Keyword,ACell> updated=P2PLattice.P2P_LATTICE.merge(base,incoming.dissoc(P2PLattice.KEY_NODES));
			AHashMap<ACell,SignedData<ACell>> heldNodes=nodes(base);
			AHashMap<ACell,SignedData<ACell>> kept=mergeRegistry(heldNodes,nodes(incoming));
			if (kept!=heldNodes) updated=updated.assoc(P2PLattice.KEY_NODES,kept);
			// A region that takes nothing new is left as it was
			return Cells.equals(updated,base)?held:updated;
		});
	}

	/** Merges the incoming records this node wants into those it holds. */
	private AHashMap<ACell,SignedData<ACell>> mergeRegistry(AHashMap<ACell,SignedData<ACell>> own,
			AHashMap<ACell,SignedData<ACell>> other) {
		List<Offer> offers=differences(own,other);
		if (offers.isEmpty()) return own;
		// Read inside the caller's update, which may run more than once: both are
		// lock-free snapshots
		Set<AccountKey> pins=pinned();
		ToIntFunction<AccountKey> standing=standings.get();

		List<Candidate> arrivals=new ArrayList<>();
		AHashMap<ACell,SignedData<ACell>> result=own;
		for (Offer offer:offers) {
			AccountKey node=offer.node();
			boolean isOwn=node.equals(ownKey);
			if (refused.test(node)) continue;
			if (!isOwn && ACell.getMemorySize(offer.incoming())>P2PLattice.MAX_NODE_RECORD_SIZE) continue;
			Candidate arrival=null;
			if (offer.held()==null && !pins.contains(node)) {
				arrival=candidate(node,offer.incoming(),standing);
				if (outranked(arrival,own,pins,standing)) continue;
			}
			var rule=P2PLattice.NODES_LATTICE.signingFor(node);
			SignedData<ACell> winner=(rule==null)?null:rule.merge(offer.held(),offer.incoming());
			if (winner==null || Cells.equals(winner,offer.held())) continue;
			if (!isOwn && !NodeDirectory.isValid(node,winner)) continue;
			if (arrival==null) {
				result=result.assoc(node,winner);
			} else {
				arrivals.add(arrival);
			}
		}
		return bound(result,arrivals,pins,standing);
	}

	/** Keeps arrivals while there is room, otherwise the best of them and the counted records held. */
	private AHashMap<ACell,SignedData<ACell>> bound(AHashMap<ACell,SignedData<ACell>> result,
			List<Candidate> arrivals,Set<AccountKey> pins,ToIntFunction<AccountKey> standing) {
		long counted=result.count();
		for (AccountKey pin:pins) if (result.containsKey(pin)) counted--;
		int max=maxRecords;
		if (counted+arrivals.size()<=max) {
			for (Candidate arrival:arrivals) result=result.assoc(arrival.node(),arrival.record());
			return result;
		}
		List<Candidate> ranked=counted(result,pins,standing);
		ranked.addAll(arrivals);
		ranked.sort(null);
		for (int i=0; i<ranked.size(); i++) {
			Candidate candidate=ranked.get(i);
			boolean held=result.containsKey(candidate.node());
			if (i<max) {
				if (!held) result=result.assoc(candidate.node(),candidate.record());
			} else if (held) {
				result=result.dissoc(candidate.node());
			}
		}
		return result;
	}

	/** Whether a new node ranks below the lowest counted record of a full registry. */
	private boolean outranked(Candidate arrival,AHashMap<ACell,SignedData<ACell>> held,Set<AccountKey> pins,
			ToIntFunction<AccountKey> standing) {
		Cutoff current=cutoff;
		if (current==null || current.registry()!=held) {
			List<Candidate> ranked=counted(held,pins,standing);
			Candidate lowest=null;
			for (Candidate candidate:ranked) {
				if (lowest==null || candidate.compareTo(lowest)>0) lowest=candidate;
			}
			current=new Cutoff(held,ranked.size()>=maxRecords,lowest);
			cutoff=current;
		}
		if (!current.full()) return false;
		return current.lowest()==null || arrival.compareTo(current.lowest())>0;
	}

	/** The counted records of a registry as ranked candidates. */
	private List<Candidate> counted(AHashMap<ACell,SignedData<ACell>> registry,Set<AccountKey> pins,
			ToIntFunction<AccountKey> standing) {
		List<Candidate> ranked=new ArrayList<>();
		for (Map.Entry<ACell,SignedData<ACell>> entry:registry.entrySet()) {
			AccountKey node=RT.ensureAccountKey(entry.getKey());
			if (node==null || pins.contains(node)) continue;
			ranked.add(candidate(node,entry.getValue(),standing));
		}
		return ranked;
	}

	private Candidate candidate(AccountKey node,SignedData<ACell> record,ToIntFunction<AccountKey> standing) {
		return new Candidate(node,record,standing.applyAsInt(node),salt.append(node).getContentHash());
	}

	/** Nodes whose records are kept and not counted. */
	private Set<AccountKey> pinned() {
		Set<AccountKey> pins=new HashSet<>(pinned.get());
		if (ownKey!=null) pins.add(ownKey);
		return pins;
	}

	/**
	 * Incoming records that differ from those held, keyed by node. The map comparison
	 * skips branches the two registries share.
	 */
	@SuppressWarnings({"unchecked","rawtypes"})
	private static List<Offer> differences(AHashMap<ACell,SignedData<ACell>> own,AHashMap<ACell,SignedData<ACell>> other) {
		List<Offer> offers=new ArrayList<>();
		((AHashMap)own).mergeDifferences(other,new MergeFunction<ACell>() {
			@Override public ACell merge(ACell a,ACell b) {
				throw new IllegalStateException("Registry merge requires the entry key");
			}
			@Override public ACell merge(Object key,ACell a,ACell b) {
				if (b instanceof SignedData<?> incoming && !Cells.equals(a,b)) {
					AccountKey node=(key instanceof ACell cell)?RT.ensureAccountKey(cell):null;
					if (node!=null) offers.add(new Offer(node,(SignedData<ACell>)a,(SignedData<ACell>)incoming));
				}
				return a;
			}
		});
		return offers;
	}

	@SuppressWarnings("unchecked")
	private static AHashMap<ACell,SignedData<ACell>> nodes(Index<Keyword,ACell> region) {
		ACell nodes=(region==null)?null:region.get(P2PLattice.KEY_NODES);
		return (nodes instanceof AHashMap<?,?> map)?(AHashMap<ACell,SignedData<ACell>>)map:Maps.empty();
	}

	/** An incoming record that differs from the held one, if any. */
	private record Offer(AccountKey node,SignedData<ACell> held,SignedData<ACell> incoming) {}

	/** The lowest counted record of one registry value, and whether that registry is full. */
	private record Cutoff(AHashMap<ACell,SignedData<ACell>> registry,boolean full,Candidate lowest) {}

	/** A record competing for the counted places, by standing then salted rank. */
	private record Candidate(AccountKey node,SignedData<ACell> record,int standing,Hash rank) implements Comparable<Candidate> {
		@Override
		public int compareTo(Candidate other) {
			int c=Integer.compare(standing,other.standing);
			return (c!=0)?c:rank.compareTo(other.rank);
		}
	}
}
