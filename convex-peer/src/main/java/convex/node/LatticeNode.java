package convex.node;

import java.io.Closeable;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import convex.api.Convex;
import convex.auth.did.DIDKeyAuthorizer;
import convex.core.crypto.AKeyPair;
import convex.core.data.ACell;
import convex.core.data.AString;
import convex.core.message.ChallengeSigner;
import convex.core.message.Message;
import convex.lattice.LatticeHost;
import convex.net.PeerName;
import convex.net.PeerResolver;
import convex.net.PeerTarget;

/**
 * Optional TCP networking for an existing host, with dynamically created
 * propagator groups. Owns the server, groups and listeners, but not their stores.
 * Inbound connections are denied unless
 * the caller selects a policy through {@link #getListener()} or explicitly calls
 * {@link #serveAllInbound(LatticePropagator)}. Further listeners, from
 * {@link #addListener(NodeConfig)}, deny until given a selector of their own.
 *
 * <p>The node takes no lock of its own. Its identity, serving policy and peer
 * methods never wait, so network callbacks such as a listener's selector may call
 * them. Launch, stopping ingress, close, adding a listener and creating or removing
 * a group wait for sockets, groups or the host: call them from management code, not
 * from a callback those waits depend on. Stopping ingress or closing while launch
 * runs closes every listener for good, so launch fails if it has not yet opened them
 * all.</p>
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
	/**
	 * This node's groups, which every listener of the node reads as the groups it may
	 * assign connections to. A group is added once attached and removed before its
	 * listeners close its sockets.
	 */
	private final Set<LatticePropagator> groups=ConcurrentHashMap.newKeySet();
	private final LatticeListener listener;
	/** Every listener this node opens and closes, its own first. */
	private final List<LatticeListener> listeners=new CopyOnWriteArrayList<>();
	/**
	 * Raised by stopping ingress and by close before they close the listeners. Adding a
	 * listener reads it after adding to {@link #listeners}, so either the closing call
	 * closes the new listener or the new listener is never opened.
	 */
	private volatile boolean stopped;
	/** The configured identity; groups get only its signer. */
	private volatile Identity identity=Identity.NONE;
	/** The resolver for peer names, or null until set or first used. */
	private final AtomicReference<PeerResolver> peerResolver=new AtomicReference<>();
	/** Peers kept by {@link #keepPeer}, stopped with their group or the node. */
	private final Map<CompletableFuture<Void>,LatticePropagator> keptPeers=new ConcurrentHashMap<>();

	/** A primary DID with the key pair, if configured as one, and the signer proving it. */
	private record Identity(AString did,AKeyPair keyPair,ChallengeSigner signer) {
		static final Identity NONE=new Identity(null,null,null);
	}

	/** Assembles networking without starting workers or opening a socket. */
	public LatticeNode(LatticeHost<V> host,NodeConfig config) {
		server=new NodeServer<>(host,config);
		listener=new LatticeListener(config,groups);
		listeners.add(listener);
	}

	/** Configures a primary did:key identity, or clears it when both arguments are null. */
	public LatticeNode<V> setIdentity(AString did,AKeyPair keyPair) {
		return setIdentity(did,keyPair,DIDKeyAuthorizer.CONVEX);
	}

	/**
	 * Configures the primary DID and a key authorised for it, as
	 * {@link #setIdentity(AString, ChallengeSigner, DIDKeyAuthorizer)} does with a
	 * signer for the key in the lattice challenge context. Pass null for both DID and
	 * key to clear the default.
	 */
	public LatticeNode<V> setIdentity(AString did,AKeyPair keyPair,DIDKeyAuthorizer authorizer) {
		ChallengeSigner signer=(keyPair==null)?null
			:ChallengeSigner.create(keyPair,Message.LATTICE_PEER_CHALLENGE_CONTEXT);
		identity=checkedIdentity(did,keyPair,signer,authorizer);
		return this;
	}

	/**
	 * Configures the primary DID and a signer proving a key authorised for it, so the
	 * node never holds the key pair. The authoriser must use authenticated resolution
	 * data for indirect DIDs. The binding is checked before changing configuration; no
	 * network resolution is performed here. New groups get this signer; existing
	 * groups retain their independently configured transport identities. Pass null for
	 * both DID and signer to clear the default.
	 *
	 * @throws IllegalArgumentException if the key is not authorised for the DID, or the
	 *         signer is for another challenge context
	 */
	public LatticeNode<V> setIdentity(AString did,ChallengeSigner signer,DIDKeyAuthorizer authorizer) {
		identity=checkedIdentity(did,null,signer,authorizer);
		return this;
	}

	private static Identity checkedIdentity(AString did,AKeyPair keyPair,ChallengeSigner signer,
			DIDKeyAuthorizer authorizer) {
		if (did==null && signer==null) return Identity.NONE;
		if (did==null || signer==null || authorizer==null
				|| !authorizer.safeAuthorises(did,signer.getAccountKey())) {
			throw new IllegalArgumentException("Node key is not authorised for its primary DID");
		}
		if (!Message.LATTICE_PEER_CHALLENGE_CONTEXT.equals(signer.getContext())) {
			throw new IllegalArgumentException("Lattice peers need a signer in the lattice challenge context");
		}
		return new Identity(did,keyPair,signer);
	}

	/** Primary DID, or null when no identity is configured. */
	public AString getDID() { return identity.did(); }

	/** The key pair configured as the primary identity, or null, including when a signer was. */
	public AKeyPair getKeyPair() { return identity.keyPair(); }

	/**
	 * Creates and registers a group with default limits, as
	 * {@link #createPropagator(LatticePropagatorConfig)} does. The node's configuration
	 * is not inherited: a node and its groups read separate configurations.
	 */
	public LatticePropagator createPropagator() {
		return createPropagator(LatticePropagatorConfig.create());
	}

	/**
	 * Creates and registers a group that publishes the host's value unfiltered, as
	 * {@link #createPropagator(LatticePropagatorConfig, LatticeFilter)} does.
	 *
	 * @param config the group's route, queue and publication limits
	 */
	public LatticePropagator createPropagator(LatticePropagatorConfig config) {
		return createPropagator(config,value -> value);
	}

	/**
	 * Creates and registers a group over the host store with the given limits and
	 * publication filter, starting it if already running. Every listener of this node
	 * may assign connections to it. It proves the node's configured identity through
	 * the node's challenge signer, never holding the key pair, or has none without an
	 * identity. The group can override this through
	 * {@link LatticePropagator#setTransportSigner(ChallengeSigner)}.
	 *
	 * @param config the group's route, queue and publication limits
	 * @param filter what the group publishes of the host's value, as
	 *        {@link LatticePropagator#setPublicationFilter} takes it
	 * @throws IllegalArgumentException if the filter is null
	 */
	public LatticePropagator createPropagator(LatticePropagatorConfig config,LatticeFilter<V> filter) {
		if (filter==null) throw new IllegalArgumentException("Publication filter must not be null");
		var propagator=new LatticePropagator(server.getStore(),config);
		propagator.setTransportSigner(identity.signer());
		propagator.setPublicationFilter(filter);
		server.addPropagator(propagator);
		groups.add(propagator);
		return propagator;
	}

	/** Revokes inbound assignments on every listener and closes this group; other groups continue running. */
	public boolean removePropagator(LatticePropagator propagator) {
		if (propagator==null) throw new IllegalArgumentException("Propagator must not be null");
		stopKeeping(propagator);
		// Every listener reads this set, so removal stops new assignments on all of them
		if (groups.remove(propagator)) {
			for (LatticeListener each:listeners) each.revoke(propagator);
		}
		return server.removePropagator(propagator);
	}

	/**
	 * Adds a listener on another port, with its own physical limits, for groups that
	 * should be reached separately, such as a trusted or replication port. Every group
	 * of this node may be assigned through it, but it denies every connection until
	 * given a selector. The node opens it with its own listener, or at once if already
	 * running, and closes it whenever it closes its own.
	 *
	 * @param config the listener's port and physical limits
	 * @return the listener, for its selector
	 * @throws IOException if the node is running and the listener cannot bind or start;
	 *         it is then not added
	 * @throws InterruptedException if opening it is interrupted
	 * @throws IllegalStateException if the node stops ingress or closes while opening it
	 */
	public LatticeListener addListener(NodeConfig config) throws IOException,InterruptedException {
		var added=new LatticeListener(config,groups);
		listeners.add(added);
		// Before the server runs, launch opens it, since launch reads the listeners after
		// starting the server. Launch may open it too; opening it again does nothing.
		if (!stopped && server.isRunning()) {
			try {
				added.launch();
			} catch (IOException | InterruptedException | RuntimeException | Error e) {
				listeners.remove(added);
				throw e;
			}
		}
		return added;
	}

	/**
	 * Sets how this node resolves peer names. The default resolves {@code did:key} and
	 * {@code did:web} and keeps no node records; a node that keeps them supplies a
	 * resolver that locates peers by key.
	 */
	public LatticeNode<V> setPeerResolver(PeerResolver resolver) {
		if (resolver==null) throw new IllegalArgumentException("Peer resolver must not be null");
		peerResolver.set(resolver);
		return this;
	}

	/** The resolver for peer names, created on first use if none was set. */
	public PeerResolver getPeerResolver() {
		PeerResolver resolver=peerResolver.get();
		if (resolver!=null) return resolver;
		// Racing first uses may each create one; all but the first installed are discarded
		PeerResolver created=PeerResolver.create();
		PeerResolver installed=peerResolver.compareAndExchange(null,created);
		return (installed==null) ? created : installed;
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
		keptPeers.put(keeper.kept,propagator);
		keeper.kept.whenComplete((value,error) -> keptPeers.remove(keeper.kept));
		keeper.start(admitted);
		return keeper.kept;
	}

	/** Stops keeping the peers of one group, or of every group when null. */
	private void stopKeeping(LatticePropagator propagator) {
		for (var entry:keptPeers.entrySet()) {
			if (propagator==null || entry.getValue()==propagator) entry.getKey().cancel(false);
		}
	}

	/**
	 * Explicitly serves this group's view to all inbound connections on this node's
	 * own listener. Assignment grants access to the view, not authentication or an
	 * outbound gossip route. May be called while running. New assignments use this
	 * group; established sockets keep their existing assignment. Private deployments
	 * can configure the listener's selector instead.
	 *
	 * @throws IllegalArgumentException if the group was not created by this node, or
	 *         has been removed
	 */
	public LatticeNode<V> serveAllInbound(LatticePropagator propagator) {
		if (propagator==null || !groups.contains(propagator)) {
			throw new IllegalArgumentException("Propagator does not belong to this network");
		}
		listener.setSelector(connection -> propagator);
		return this;
	}

	/** Starts publication before accepting connections on any listener. Failed launch closes owned resources. */
	public LatticeNode<V> launch() throws IOException,InterruptedException {
		try {
			server.launch();
			for (LatticeListener each:listeners) each.launch();
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

	/** This node's own listener, on the port its configuration names, for an explicit inbound selection policy. */
	public LatticeListener getListener() { return listener; }

	/** Actual bound address of this node's own listener, or null when listening is disabled. */
	public InetSocketAddress getHostAddress() { return listener.getHostAddress(); }

	/**
	 * Stops networking changing the host: closes every listener for good, then drains
	 * the server's accepted inbound work, as {@link NodeServer#stopIngress()} does.
	 * Publication continues; see convex-peer/docs/SHUTDOWN.md for the service order.
	 */
	public void stopIngress() {
		stopped=true;
		try {
			Throwable failure=closeListeners();
			if (failure instanceof RuntimeException e) throw e;
			if (failure instanceof Error e) throw e;
		} finally {
			server.stopIngress();
		}
	}

	/** Closes every listener, each even if another fails, and returns the first failure, with any later one suppressed. */
	private Throwable closeListeners() {
		Throwable failure=null;
		for (LatticeListener each:listeners) {
			try {
				each.close();
			} catch (RuntimeException | Error e) {
				if (failure==null) failure=e;
				else failure.addSuppressed(e);
			}
		}
		return failure;
	}

	/**
	 * Stops acceptance and ingress, sends what the host has published, then detaches
	 * the server from the host. Both steps always run; the first failure is thrown,
	 * with any later one suppressed.
	 */
	@Override
	public void close() throws IOException {
		stopped=true;
		stopKeeping(null);
		Throwable failure=closeListeners();
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
