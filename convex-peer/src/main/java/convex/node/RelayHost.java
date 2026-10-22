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
 * Host assembly from node configuration.
 *
 * <p>{@link #openHost} opens a store-backed root the way node configuration asks:
 * the retained root is adopted when restore is enabled and the store is persistent,
 * and the root supports exclusive transactions when configured. The caller then
 * configures the applications that receive incoming values. The relay variant,
 * {@link #open}, configures one live application that accepts the whole supplied
 * lattice. A node with an application of its own opens the host the same way and
 * configures that application instead.</p>
 *
 * <p>This is host assembly, not node behaviour. A {@link NodeServer} replicates
 * whatever host it is given and merges nothing itself; the configured applications
 * admit incoming values under their own policy. The policy is supplied up front
 * because a live application resolves it when constructed.</p>
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
	 * Opens a relay host: {@link #openHost} with one live application over the whole
	 * lattice, under the supplied policy. Admission rules belong to that lattice;
	 * node configuration does not modify them.
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
		LatticeHost<V> host=openHost(lattice.zero(),store,config);
		host.setContext(context);
		host.configureApplications(List.of(LatticeApplication.live(lattice,host)));
		return host;
	}

	/**
	 * Opens a host over a store as node configuration directs, with no applications
	 * configured. When configuration permits restore and the store is persistent, the
	 * retained root is adopted as the initial value; otherwise the host starts at
	 * {@code initial}. The root supports exclusive transactions when configured.
	 *
	 * @param <V> root value type
	 * @param initial value used when nothing is restored
	 * @param store authoritative cell store
	 * @param config node configuration, or {@code null} for defaults
	 * @return host with no applications configured
	 * @throws IOException if the retained root cannot be read
	 */
	public static <V extends ACell> LatticeHost<V> openHost(V initial,AStore store,
			NodeConfig config) throws IOException {
		if (store==null) throw new IllegalArgumentException("Store must not be null");
		NodeConfig settings=(config==null)?NodeConfig.create():config;
		V value=initial;
		if (settings.isRestore() && store.isPersistent()) {
			V retained=store.getRootData();
			if (retained!=null) value=retained;
		}
		Root<V> storage=settings.isTransactionalRoot()
			?Cursors.transactionalRoot(value)
			:Cursors.root(value);
		return new LatticeHost<>(storage,store);
	}

}
