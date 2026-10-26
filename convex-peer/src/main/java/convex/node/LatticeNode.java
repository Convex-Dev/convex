package convex.node;

import java.io.Closeable;
import java.io.IOException;
import java.net.InetSocketAddress;

import convex.auth.did.DIDKeyAuthorizer;
import convex.core.crypto.AKeyPair;
import convex.core.data.ACell;
import convex.core.data.AString;
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
	private AString did;
	private AKeyPair keyPair;

	/** Assembles networking without starting workers or opening a socket. */
	public LatticeNode(LatticeHost<V> host,NodeConfig config) {
		server=new NodeServer<>(host,config);
		listener=new LatticeListener(config);
	}

	/** Configures a primary did:key identity, or clears it when both arguments are null. */
	public LatticeNode<V> setIdentity(AString did,AKeyPair keyPair) {
		return setIdentity(did,keyPair,DIDKeyAuthorizer.CONVEX);
	}

	/**
	 * Configures the primary DID and a key authorised for it. The authoriser must
	 * use authenticated resolution data for indirect DIDs. The binding is checked
	 * before changing configuration; no network resolution is performed here.
	 * New groups copy this key. Existing groups retain their independently
	 * configured transport keys. Pass null for both DID and key to clear the default.
	 */
	public synchronized LatticeNode<V> setIdentity(AString did,AKeyPair keyPair,DIDKeyAuthorizer authorizer) {
		if (did!=null || keyPair!=null) {
			if (did==null || keyPair==null || authorizer==null
					|| !authorizer.safeAuthorises(did,keyPair.getAccountKey())) {
				throw new IllegalArgumentException("Node key is not authorised for its primary DID");
			}
		}
		this.did=did;
		this.keyPair=keyPair;
		return this;
	}

	/** Primary DID, or null when no identity is configured. */
	public synchronized AString getDID() { return did; }

	/** Configured primary key, or null. No identity or key is generated implicitly. */
	public synchronized AKeyPair getKeyPair() { return keyPair; }

	/**
	 * Creates and registers a group over the host store, starting it if already running.
	 * Its transport key defaults to the node's configured key, or null without an identity.
	 * The group can override this default through {@link LatticePropagator#setTransportKeyPair(AKeyPair)}.
	 */
	public synchronized LatticePropagator createPropagator() {
		var propagator=new LatticePropagator(server.getStore());
		propagator.setTransportKeyPair(keyPair);
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

	/**
	 * Stops networking changing the host: closes the listener, then drains the
	 * server's accepted inbound work. Publication continues to outbound routes, so
	 * sync the application drafts to keep and close the host next; its final
	 * checkpoint reaches peers when this node closes. See
	 * {@link NodeServer#stopIngress()}.
	 */
	public synchronized void stopIngress() {
		try {
			listener.close();
		} finally {
			server.stopIngress();
		}
	}

	/**
	 * Stops acceptance and ingress, sends what the host has published, then detaches
	 * the server from the host. Both steps always run; the first failure is thrown,
	 * with any later one suppressed.
	 */
	@Override
	public synchronized void close() throws IOException {
		Throwable failure=null;
		try {
			listener.close();
		} catch (RuntimeException | Error e) {
			failure=e;
		}
		try {
			server.close();
		} catch (IOException | RuntimeException | Error e) {
			// Report the first failure; a later one must not hide it
			if (failure==null) throw e;
			failure.addSuppressed(e);
		}
		if (failure instanceof RuntimeException e) throw e;
		if (failure instanceof Error e) throw e;
	}
}
