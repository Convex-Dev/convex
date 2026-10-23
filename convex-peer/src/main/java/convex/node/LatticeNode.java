package convex.node;

import java.io.Closeable;
import java.io.IOException;
import java.net.InetSocketAddress;

import convex.core.data.ACell;
import convex.lattice.LatticeHost;

/**
 * Optional TCP networking for an existing host, with one explicitly supplied
 * propagation group. Owns the server, group and listener, but not the host store.
 * Configure the group before construction. Inbound connections are denied unless
 * the caller selects a policy through {@link #getListener()} or explicitly calls
 * {@link #serveAllInbound()}.
 *
 * <p>For several groups or custom transports, assemble {@link NodeServer} and
 * {@link LatticeListener} directly. This convenience owns no domain policy.</p>
 *
 * @param <V> Hosted root value type
 */
public final class LatticeNode<V extends ACell> implements Closeable {

	private final NodeServer<V> server;
	private final LatticePropagator propagator;
	private final LatticeListener listener;

	/** Assembles networking without starting workers or opening a socket. */
	public LatticeNode(LatticeHost<V> host,NodeConfig config,LatticePropagator propagator) {
		server=new NodeServer<>(host,config);
		server.addPropagator(propagator);
		this.propagator=propagator;
		listener=new LatticeListener(config);
		listener.registerPropagator(propagator);
	}

	/**
	 * Explicitly serves this group's view to all inbound connections. Assignment
	 * grants access to the view, not authentication or an outbound gossip route.
	 * Call before launch; private deployments can instead configure the listener.
	 */
	public LatticeNode<V> serveAllInbound() {
		listener.setSelector(connection -> propagator);
		return this;
	}

	/** Starts publication before accepting connections. Failed launch closes owned resources. */
	public LatticeNode<V> launch() throws IOException,InterruptedException {
		try {
			server.launch();
			listener.launch();
			return this;
		} catch (IOException | InterruptedException | RuntimeException | Error e) {
			try {
				close();
			} catch (Throwable failure) {
				if (failure!=e) e.addSuppressed(failure);
			}
			throw e;
		}
	}

	/** The server attached to the original host. */
	public NodeServer<V> getServer() { return server; }

	/** The supplied group, for explicit peer connections, pulls and publication signals. */
	public LatticePropagator getPropagator() { return propagator; }

	/** Listener for an explicit inbound selection policy. */
	public LatticeListener getListener() { return listener; }

	/** Actual bound address, or null when listening is disabled. */
	public InetSocketAddress getHostAddress() { return listener.getHostAddress(); }

	/** Stops acceptance before draining publication, then detaches the server from the host. */
	@Override
	public void close() throws IOException {
		try {
			listener.close();
		} finally {
			server.close();
		}
	}
}
