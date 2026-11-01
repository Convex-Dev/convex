package convex.p2p;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;
import java.util.function.Supplier;

import convex.core.data.ACell;
import convex.core.data.AHashMap;
import convex.core.data.AccountKey;
import convex.core.data.Index;
import convex.core.data.Keyword;
import convex.core.data.SignedData;
import convex.lattice.ALatticeApplication;
import convex.lattice.generic.KeyedLattice;

/**
 * Connection management for the P2P application: the node registry this node holds
 * at {@code [:p2p :nodes]}, its validated view as a {@link NodeDirectory}, and what
 * this node has learned about other nodes as {@link ContactRecords}.
 *
 * <p>It holds the policy of this node's {@link NodeRegistryLattice}, the rule the
 * application merges the registry with: the bound, the blocklist, the contact
 * records by which records rank, and the nodes whose records are pinned, which are this
 * node's own and those of nodes the operator named, nodes it has an authenticated
 * route to or is authenticating one from, and its Points of Presence, all bounded by
 * configuration and connections. Evidence comes from this node's contact records,
 * never from a record itself. A dropped record leaves the directory but not the
 * connection manager's candidates.</p>
 */
final class P2PConnections extends ALatticeApplication<Index<Keyword,ACell>> {

	private final AccountKey ownKey;
	private final KeyedLattice lattice;
	private final NodeRegistryLattice registry;
	private final NodeDirectory directory;
	private final ContactRecords contacts;
	private final Set<AccountKey> named=ConcurrentHashMap.newKeySet();
	private volatile Supplier<Set<AccountKey>> routes=() -> Set.of();

	@SuppressWarnings("unchecked")
	P2PConnections(P2PApplication application,NodeRegistryLattice registry,
			BiFunction<ACell,ACell,SignedData<ACell>> signer) {
		super(application.cursor());
		this.ownKey=application.ownKey();
		this.lattice=(KeyedLattice)application.getLattice();
		this.registry=registry;
		this.directory=new NodeDirectory(application);
		// Only a node that can sign its own :local slot keeps contact records
		this.contacts=(ownKey==null || signer==null)?null:new ContactRecords(cursor(),ownKey,
			value -> (SignedData<AHashMap<Keyword,ACell>>)(SignedData<?>)signer.apply(ownKey,value),application.clock());
		registry.setPinned(this::pinned);
		if (contacts!=null) registry.setEvidence(contacts::all);
	}

	/** This node's validated view of the registry it holds. */
	NodeDirectory directory() {
		return directory;
	}

	/** This node's contact records, or null for a node without a key. */
	ContactRecords contacts() {
		return contacts;
	}

	/** Bounds the counted records this node keeps. */
	void setMaxRecords(int maxRecords) {
		registry.setMaxRecords(maxRecords);
	}

	/** Supplies the nodes this node has, or is authenticating, a route to. */
	void setRoutes(Supplier<Set<AccountKey>> routes) {
		this.routes=routes;
	}

	/** Keeps a node's record, as the operator named it. */
	void name(AccountKey node) {
		named.add(node);
	}

	/** Drops a node's record and refuses it from now on. */
	@SuppressWarnings("unchecked")
	void block(AccountKey node) {
		registry.block(node);
		cursor().updateAndGet(own -> {
			if (!(own.get(P2PLattice.KEY_P2P) instanceof Index<?,?> region)) return own;
			Index<Keyword,ACell> p2p=(Index<Keyword,ACell>)region;
			if (!(p2p.get(P2PLattice.KEY_NODES) instanceof AHashMap<?,?> nodes) || !nodes.containsKey(node)) return own;
			return own.assoc(P2PLattice.KEY_P2P,p2p.assoc(P2PLattice.KEY_NODES,((AHashMap<ACell,ACell>)nodes).dissoc(node)));
		});
		directory.refresh();
	}

	/** Syncs this node's contact records into its {@code :local} slot. */
	@Override
	protected void syncChildren() {
		if (contacts!=null) contacts.sync();
	}

	/** Merges the {@code :p2p} region of an incoming root under this node's registry rule. */
	@Override
	public Index<Keyword,ACell> merge(Index<Keyword,ACell> incoming) {
		ACell region=(incoming==null)?null:incoming.get(P2PLattice.KEY_P2P);
		if (region==null) return cursor().get();
		Index<Keyword,ACell> other=Index.of(P2PLattice.KEY_P2P,region);
		return cursor().updateAndGet(own -> lattice.merge(own,other));
	}

	/** Nodes whose records are kept and not counted. */
	private Set<AccountKey> pinned() {
		Set<AccountKey> pinned=new HashSet<>(named);
		pinned.addAll(routes.get());
		for (AccountKey pop:directory.pointsOfPresence()) pinned.add(pop);
		if (ownKey!=null) pinned.add(ownKey);
		return pinned;
	}
}
