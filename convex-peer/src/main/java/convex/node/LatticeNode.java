package convex.node;

import java.io.Closeable;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import convex.api.Convex;
import convex.auth.did.DIDKeyAuthorizer;
import convex.core.crypto.AKeyPair;
import convex.core.data.ACell;
import convex.core.data.AString;
import convex.lattice.LatticeHost;
import convex.net.PeerName;
import convex.net.PeerResolver;
import convex.net.PeerTarget;

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

	/** How long a kept peer is retried without success before it is given up, by default. */
	public static final Duration DEFAULT_KEEP_PERIOD=Duration.ofMinutes(10);

	private final NodeServer<V> server;
	private final LatticeListener listener;
	private AString did;
	private AKeyPair keyPair;
	private PeerResolver peerResolver;
	/** Peers kept by {@link #keepPeer}, stopped with their group or the node. */
	private final Map<CompletableFuture<Void>,LatticePropagator> keptPeers=new HashMap<>();

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
	 * Creates and registers a group with default limits, as
	 * {@link #createPropagator(LatticePropagatorConfig)} does. The node's configuration
	 * is not inherited: a node and its groups read separate configurations.
	 */
	public LatticePropagator createPropagator() {
		return createPropagator(LatticePropagatorConfig.create());
	}

	/**
	 * Creates and registers a group over the host store with the given limits,
	 * starting it if already running. Its transport key defaults to the node's
	 * configured key, or null without an identity. The group can override this
	 * default through {@link LatticePropagator#setTransportKeyPair(AKeyPair)}.
	 *
	 * @param config the group's route, queue and publication limits
	 */
	public synchronized LatticePropagator createPropagator(LatticePropagatorConfig config) {
		var propagator=new LatticePropagator(server.getStore(),config);
		propagator.setTransportKeyPair(keyPair);
		server.addPropagator(propagator);
		listener.registerPropagator(propagator);
		return propagator;
	}

	/** Revokes inbound assignments and closes this group; other groups continue running. */
	public synchronized boolean removePropagator(LatticePropagator propagator) {
		stopKeeping(propagator);
		listener.unregisterPropagator(propagator);
		return server.removePropagator(propagator);
	}

	/**
	 * Sets how this node resolves peer names. The default resolves {@code did:key} and
	 * {@code did:web} and keeps no node records; a node that keeps them supplies a
	 * resolver that locates peers by key.
	 */
	public synchronized LatticeNode<V> setPeerResolver(PeerResolver resolver) {
		if (resolver==null) throw new IllegalArgumentException("Peer resolver must not be null");
		this.peerResolver=resolver;
		return this;
	}

	/** The resolver for peer names, created on first use if none was set. */
	public synchronized PeerResolver getPeerResolver() {
		if (peerResolver==null) peerResolver=PeerResolver.create();
		return peerResolver;
	}

	/**
	 * Makes one attempt to connect a group to a peer by its name: resolves the name,
	 * then admits the peer through the group's connection manager, as
	 * {@link LatticeConnectionManager#connectPeer(PeerTarget)} does. The route is not
	 * redialled; see {@link #keepPeer} for a peer to keep.
	 *
	 * @param propagator group to connect
	 * @param name peer name, as {@link PeerResolver#parse(String)} reads it
	 * @return future completing with the admitted connection, or failing with why the
	 *         name could not be resolved or the peer admitted
	 */
	public CompletableFuture<Convex> connectPeer(LatticePropagator propagator,PeerName name) {
		PeerResolver resolver=getPeerResolver();
		return resolver.resolve(name).thenCompose(target -> propagator.getConnectionManager().connectPeer(target));
	}

	/**
	 * Keeps a group connected to a peer it names, with the default period, as
	 * {@link #keepPeer(LatticePropagator, PeerName, Convex, Duration)} does.
	 */
	public CompletableFuture<Void> keepPeer(LatticePropagator propagator,PeerName name,Convex admitted) {
		return keepPeer(propagator,name,admitted,DEFAULT_KEEP_PERIOD);
	}

	/**
	 * Keeps a group connected to a peer the operator named. The name is resolved again
	 * before every reconnection, after the peer could not be reached or its route went,
	 * with a backoff between attempts. A peer unreachable for a whole period is given up
	 * and reported; each lost route starts a new period.
	 *
	 * @param propagator group to connect
	 * @param name peer name, as {@link PeerResolver#parse(String)} reads it
	 * @param admitted a connection to the peer already admitted, or null to connect first
	 * @param giveUpAfter period without success after which the peer is given up
	 * @return future failing once the peer is given up; cancel it to stop keeping the
	 *         peer and release its route
	 */
	public CompletableFuture<Void> keepPeer(LatticePropagator propagator,PeerName name,Convex admitted,
			Duration giveUpAfter) {
		PeerKeeper keeper=new PeerKeeper(propagator.getConnectionManager(),getPeerResolver(),name,
			giveUpAfter.toMillis(),() -> System.nanoTime()/1_000_000L,
			(delay,task) -> CompletableFuture.delayedExecutor(delay,TimeUnit.MILLISECONDS).execute(task));
		synchronized (this) {
			keptPeers.put(keeper.kept,propagator);
		}
		keeper.kept.whenComplete((value,error) -> {
			synchronized (this) {
				keptPeers.remove(keeper.kept);
			}
		});
		keeper.start(admitted);
		return keeper.kept;
	}

	/** Stops keeping the peers of one group, or of every group when null. */
	private void stopKeeping(LatticePropagator propagator) {
		List<CompletableFuture<Void>> stopping=new ArrayList<>();
		synchronized (this) {
			for (var entry:keptPeers.entrySet()) {
				if (propagator==null || entry.getValue()==propagator) stopping.add(entry.getKey());
			}
		}
		for (CompletableFuture<Void> kept:stopping) kept.cancel(false);
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
	 * server's accepted inbound work, as {@link NodeServer#stopIngress()} does.
	 * Publication continues; see convex-peer/docs/SHUTDOWN.md for the service order.
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
		stopKeeping(null);
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
