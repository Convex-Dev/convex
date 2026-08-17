package convex.lattice;

import java.io.IOException;

import convex.core.data.ACell;

/**
 * Base class for an application composed over a hosted lattice root.
 *
 * <p>An application component works at the same cursor position as its
 * {@link RootComponent} parent. It provides the application-level composition
 * point beneath which independently located domain regions can be attached,
 * while persistence continues to delegate to the host.</p>
 *
 * <p>The application does not own the root, store or replication lifecycle.
 * Calling {@link #sync()} invokes the host's root publication policy;
 * {@link #flush()} remains a distinct durability boundary.</p>
 *
 * @param <V> Complete application root value type
 */
public abstract class ALatticeApplication<V extends ACell> extends ALatticeComponent<V> {

	private final RootComponent<V> host;

	/**
	 * Creates an application over an existing lattice host.
	 *
	 * @param host Root component providing the application cursor and host policy
	 */
	protected ALatticeApplication(RootComponent<V> host) {
		super(host,requireHost(host).cursor());
		this.host=host;
	}

	private static <V extends ACell> RootComponent<V> requireHost(RootComponent<V> host) {
		if (host==null) throw new IllegalArgumentException("Application host must not be null");
		return host;
	}

	/**
	 * Returns the application host for specialised application infrastructure.
	 *
	 * @return Hosted root component
	 */
	protected final RootComponent<V> host() {
		return host;
	}

	/**
	 * Requests the host store's physical durability barrier.
	 *
	 * <p>Call {@link #sync()} first when working state must become the selected,
	 * published root. Flush deliberately does not move or merge any cursor.</p>
	 *
	 * @throws IOException If the store flush fails
	 */
	public void flush() throws IOException {
		host.flush();
	}
}
