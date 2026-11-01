package convex.p2p;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import convex.core.data.ACell;
import convex.core.data.AHashMap;
import convex.core.data.AccountKey;
import convex.core.data.Blob;
import convex.core.data.Cells;
import convex.core.data.Hash;
import convex.core.data.Keyword;
import convex.core.data.Maps;
import convex.core.data.SignedData;
import convex.core.lang.RT;
import convex.core.util.MergeFunction;
import convex.lattice.ALattice;
import convex.lattice.generic.OwnerMapLattice;

/**
 * This node's rule for the node registry at {@code [:p2p :nodes]}: the owner rule,
 * applied only to the records this node wants.
 *
 * <p>Nodes that accept every record converge under the owner rule, which takes each
 * owner's newest signed record. A node merging with this lattice holds a subset of
 * that: an incoming record that differs from the one held is checked first, cheapest
 * check first, and merged only if it passes. The checks, the pins and the ranking
 * are those of retention in {@code P2P_DESIGN.md} §4.2. The salt is drawn when this
 * lattice is created, so the order is stable while the node runs.</p>
 *
 * <p>Held records are not checked again: they passed when they were merged. The
 * lowest held rank used to turn new nodes away is worked out once for each registry
 * value, so evidence and pins that change in between take effect at the next
 * change. Every node keeps a different subset, so each exchange offers records
 * turned away before, and each costs only a comparison.</p>
 */
final class NodeRegistryLattice extends ALattice<AHashMap<ACell,SignedData<ACell>>> {

	/** Bound on counted records, the contact-record cap, so each can carry contact history. */
	static final int DEFAULT_MAX_RECORDS=ContactRecords.MAX_NODES;

	private static final OwnerMapLattice<ACell,ACell> OWNERS=P2PLattice.NODES_LATTICE;

	private final AccountKey ownKey;
	private final Blob salt=Blob.createRandom(new SecureRandom(),32);
	private final Set<AccountKey> blocked=ConcurrentHashMap.newKeySet();
	private volatile Supplier<Set<AccountKey>> pinned=Set::of;
	private volatile Supplier<AHashMap<AccountKey,AHashMap<Keyword,ACell>>> evidence=Maps::empty;
	private volatile int maxRecords=DEFAULT_MAX_RECORDS;
	private volatile Cutoff cutoff;

	/**
	 * @param ownKey this node's key, whose record is always kept, or null for a keyless node
	 */
	NodeRegistryLattice(AccountKey ownKey) {
		this.ownKey=ownKey;
	}

	/** Bounds the counted records. */
	void setMaxRecords(int maxRecords) {
		if (maxRecords<0) throw new IllegalArgumentException("Record bound must not be negative");
		this.maxRecords=maxRecords;
		cutoff=null;
	}

	/** Supplies the nodes whose records are kept and not counted, besides this node's own. */
	void setPinned(Supplier<Set<AccountKey>> pinned) {
		this.pinned=pinned;
	}

	/** Supplies this node's contact records, by which records rank. */
	void setEvidence(Supplier<AHashMap<AccountKey,AHashMap<Keyword,ACell>>> evidence) {
		this.evidence=evidence;
	}

	/** Refuses a node's record from now on. The caller drops any record held. */
	void block(AccountKey node) {
		blocked.add(node);
	}

	/** Whether this node refuses a node's record. */
	boolean isBlocked(AccountKey node) {
		return blocked.contains(node);
	}

	@Override
	public AHashMap<ACell,SignedData<ACell>> merge(AHashMap<ACell,SignedData<ACell>> own,
			AHashMap<ACell,SignedData<ACell>> other) {
		if (other==null || other==own) return own;
		if (!checkForeign(other)) throw new IllegalArgumentException("Invalid node registry");
		if (own==null) own=zero();
		List<Offer> offers=differences(own,other);
		if (offers.isEmpty()) return own;
		// Read inside the caller's update, which may run more than once: both are
		// lock-free snapshots
		Set<AccountKey> pins=pins();
		AHashMap<AccountKey,AHashMap<Keyword,ACell>> contacts=evidence.get();

		List<Candidate> arrivals=new ArrayList<>();
		AHashMap<ACell,SignedData<ACell>> result=own;
		for (Offer offer:offers) {
			AccountKey node=offer.node();
			boolean isOwn=node.equals(ownKey);
			if (blocked.contains(node)) continue;
			if (!isOwn && ACell.getMemorySize(offer.incoming())>P2PLattice.MAX_NODE_RECORD_SIZE) continue;
			Candidate arrival=null;
			if (offer.held()==null && !pins.contains(node)) {
				arrival=candidate(node,offer.incoming(),contacts);
				if (outranked(arrival,own,pins,contacts)) continue;
			}
			var rule=OWNERS.signingFor(node);
			SignedData<ACell> winner=(rule==null)?null:rule.merge(offer.held(),offer.incoming());
			if (winner==null || Cells.equals(winner,offer.held())) continue;
			if (!isOwn && !NodeDirectory.isValid(node,winner)) continue;
			if (arrival==null) {
				result=result.assoc(node,winner);
			} else {
				arrivals.add(arrival);
			}
		}
		return bound(result,arrivals,pins,contacts);
	}

	/** Keeps arrivals while there is room, otherwise the best of them and the counted records held. */
	private AHashMap<ACell,SignedData<ACell>> bound(AHashMap<ACell,SignedData<ACell>> result,
			List<Candidate> arrivals,Set<AccountKey> pins,AHashMap<AccountKey,AHashMap<Keyword,ACell>> contacts) {
		long counted=result.count();
		for (AccountKey pin:pins) if (result.containsKey(pin)) counted--;
		int max=maxRecords;
		if (counted+arrivals.size()<=max) {
			for (Candidate arrival:arrivals) result=result.assoc(arrival.node(),arrival.record());
			return result;
		}
		List<Candidate> ranked=counted(result,pins,contacts);
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
			AHashMap<AccountKey,AHashMap<Keyword,ACell>> contacts) {
		Cutoff current=cutoff;
		if (current==null || current.registry()!=held) {
			List<Candidate> ranked=counted(held,pins,contacts);
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
			AHashMap<AccountKey,AHashMap<Keyword,ACell>> contacts) {
		List<Candidate> ranked=new ArrayList<>();
		for (Map.Entry<ACell,SignedData<ACell>> entry:registry.entrySet()) {
			AccountKey node=RT.ensureAccountKey(entry.getKey());
			if (node==null || pins.contains(node)) continue;
			ranked.add(candidate(node,entry.getValue(),contacts));
		}
		return ranked;
	}

	private Candidate candidate(AccountKey node,SignedData<ACell> record,
			AHashMap<AccountKey,AHashMap<Keyword,ACell>> contacts) {
		return new Candidate(node,record,standing(contacts.get(node)),salt.append(node).getContentHash());
	}

	/** Pinned nodes, with this node's own. */
	private Set<AccountKey> pins() {
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

	/** Orders records: answered, then never reached, then failed. */
	private static int standing(AHashMap<Keyword,ACell> contact) {
		if (ContactRecords.answered(contact)) return 0;
		return ContactRecords.failing(contact)?2:1;
	}

	@Override
	public AHashMap<ACell,SignedData<ACell>> zero() {
		return OWNERS.zero();
	}

	@Override
	public boolean checkForeign(AHashMap<ACell,SignedData<ACell>> value) {
		return OWNERS.checkForeign(value);
	}

	@Override
	public <T extends ACell> ALattice<T> path(ACell childKey) {
		return OWNERS.path(childKey);
	}

	@Override
	public ACell resolveKey(ACell key) {
		return OWNERS.resolveKey(key);
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
