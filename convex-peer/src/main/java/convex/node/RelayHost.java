package convex.node;

import java.io.IOException;
import java.util.List;

import convex.core.data.ACell;
import convex.core.store.AStore;
import convex.lattice.ALattice;
import convex.lattice.LatticeApplication;
import convex.lattice.LatticeContext;
import convex.lattice.LatticeHost;
import convex.lattice.cursor.Cursors;
import convex.lattice.cursor.Root;

/**
 * Assembles the host for a relay: a store-backed root with one live application
 * that accepts the whole supplied lattice.
 *
 * <p>This is host assembly, not node behaviour. A {@link NodeServer} replicates
 * whatever host it is given and merges nothing itself; the relay application is
 * what admits incoming values, under the supplied policy. Node configuration
 * selects whether the retained root is restored, whether the root supports
 * exclusive transactions, and the maximum future timestamp skew applied to the
 * policy. The policy is supplied up front because a live application resolves it
 * when constructed.</p>
 */
public final class RelayHost {
	private RelayHost() {}

	/**
	 * Opens a relay host with an empty policy.
	 *
	 * @param <V> root value type
	 * @param lattice the complete lattice the relay accepts and serves
	 * @param store authoritative cell store
	 * @param config node configuration, or {@code null} for defaults
	 * @return host with the relay application configured to receive incoming values
	 * @throws IOException if the retained root cannot be read
	 */
	public static <V extends ACell> LatticeHost<V> open(ALattice<V> lattice,AStore store,
			NodeConfig config) throws IOException {
		return open(lattice,store,config,LatticeContext.EMPTY);
	}

	/**
	 * Opens a relay host.
	 *
	 * <p>When configuration permits restore and the store is persistent, the retained
	 * root is adopted as the initial value; otherwise the host starts at the lattice's
	 * empty value. The configured maximum future timestamp skew overrides the
	 * context's own when it is set explicitly.</p>
	 *
	 * @param <V> root value type
	 * @param lattice the complete lattice the relay accepts and serves
	 * @param store authoritative cell store
	 * @param config node configuration, or {@code null} for defaults
	 * @param context write and merge policy for the relay application, or {@code null} for none
	 * @return host with the relay application configured to receive incoming values
	 * @throws IOException if the retained root cannot be read
	 */
	public static <V extends ACell> LatticeHost<V> open(ALattice<V> lattice,AStore store,
			NodeConfig config,LatticeContext context) throws IOException {
		if (lattice==null) throw new IllegalArgumentException("Lattice must not be null");
		if (store==null) throw new IllegalArgumentException("Store must not be null");
		NodeConfig settings=(config==null)?NodeConfig.create():config;
		LatticeContext policy=(context==null)?LatticeContext.EMPTY:context;

		V initial=lattice.zero();
		if (settings.isRestore() && store.isPersistent()) {
			V retained=store.getRootData();
			if (retained!=null) initial=retained;
		}
		Root<V> storage=settings.isTransactionalRoot()
			?Cursors.transactionalRoot(initial)
			:Cursors.root(initial);
		LatticeHost<V> host=new LatticeHost<>(storage,store);

		long configuredSkew=settings.getMaxFutureTimestampSkew();
		long effectiveSkew=settings.getMap().containsKey(NodeConfig.MAX_FUTURE_TIMESTAMP_SKEW)
			?configuredSkew:policy.getMaxFutureTimestampSkew(configuredSkew);
		host.setContext(policy.withMaxFutureTimestampSkew(effectiveSkew));
		host.configureApplications(List.of(LatticeApplication.live(lattice,host)));
		return host;
	}
}
