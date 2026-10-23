package convex.node;

import java.io.Closeable;
import java.io.IOException;
import java.net.InetSocketAddress;

import convex.core.data.ACell;
import convex.lattice.LatticeHost;

/**
 * Optional TCP networking for an existing host, with dynamically created
 * propagation groups. Owns the server, groups and listener, but not their stores.
 * Inbound connections are denied unless
 * the caller selects a policy through {@link #getListener()} or explicitly calls
 * {@link #serveAllInbound(LatticePropagator)}.
 *
 * <p>For custom transports, assemble {@link NodeServer} and
 * {@link LatticeListener} directly. This convenience owns no domain policy.</p>
 *
 * @param <V> Hosted root value type
 */
public final class LatticeNode<V extends ACell> implements Closeable {

	private final NodeServer<V> server;
	private final LatticeListener listener;

	/** Assembles networking without starting workers or opening a socket. */
	public LatticeNode(LatticeHost<V> host,NodeConfig config) {
		server=new NodeServer<>(host,config);
		listener=new LatticeListener(config);
	}

	/** Creates and registers a group over the host store, starting it if already running. */
	public synchronized LatticePropagator createPropagator() {
		var propagator=new LatticePropagator(server.getStore());
		server.addPropagator(propagator);
		listener.registerPropagator(propagator);
		return propagator;
	}

	/** Revokes inbound assignments and closes this group; other groups continue running. */
	public synchronized boolean removePropagator(LatticePropagator propagator) {
		listener.unregisterPropagator(propagator);
		return server.removePropagator(propagator);
	}

	/**
	 * Explicitly serves this group's view to all inbound connections. Assignment
	 * grants access to the view, not authentication or an outbound gossip route.
	 * May be called while running. New assignments use this group; established
	 * sockets keep their existing assignment. Private deployments can configure
	 * the listener's selector instead.
	 */
	public synchronized LatticeNode<V> serveAllInbound(LatticePropagator propagator) {
		if (!server.getPropagators().contains(propagator)) {
			throw new IllegalArgumentException("Propagator does not belong to this network");
		}
		listener.setSelector(connection -> propagator);
		return this;
	}

	/** Starts publication before accepting connections. Failed launch closes owned resources. */
	public synchronized LatticeNode<V> launch() throws IOException,InterruptedException {
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

	/** Listener for an explicit inbound selection policy. */
	public LatticeListener getListener() { return listener; }

	/** Actual bound address, or null when listening is disabled. */
	public InetSocketAddress getHostAddress() { return listener.getHostAddress(); }

	/** Stops acceptance before draining publication, then detaches the server from the host. */
	@Override
	public synchronized void close() throws IOException {
		try {
			listener.close();
		} finally {
			server.close();
		}
	}
}
