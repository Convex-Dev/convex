package convex.p2p;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.api.Convex;
import convex.auth.did.DID;
import convex.auth.did.DIDKeyAuthorizer;
import convex.core.Result;
import convex.core.crypto.AKeyPair;
import convex.core.data.ACell;
import convex.core.data.AHashMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.AccountKey;
import convex.core.data.Index;
import convex.core.data.Hash;
import convex.core.data.Keyword;
import convex.core.data.Maps;
import convex.core.data.SignedData;
import convex.core.data.Vectors;
import convex.core.message.AConnection;
import convex.core.message.Message;
import convex.core.message.MessageTag;
import convex.core.message.MessageType;
import convex.core.store.AStore;
import convex.etch.EtchStore;
import convex.lattice.LatticeContext;
import convex.lattice.cursor.ALatticeCursor;
import convex.lattice.generic.KeyedLattice;
import convex.node.NodeConfig;
import convex.node.NodeServer;
import convex.node.LatticeConnectionManager;
import convex.node.LatticeListener;
import convex.node.LatticePropagator;
import convex.node.LatticePropagatorConfig;
import convex.social.Social;

/**
 * A Convex peer-to-peer lattice node.
 *
 * <p>This is the main entry point for convex-p2p — a rollup package, so this is the
 * <em>only</em> node server, with no separate social or per-application node. It composes
 * a region set with the authoritative {@link NodeServer} and an application-owned
 * {@link LatticeListener}, giving a node that can query, merge and propagate lattice
 * values with other nodes over the binary protocol.</p>
 *
 * <p><b>Regions are configuration.</b> {@link #create(AStore, NodeConfig, AKeyPair)}
 * serves {@link P2PLattice#NODE_ROOT} — the P2P regions plus the bundled application
 * regions. To switch the application regions off, pass {@link P2PLattice#ROOT} to the
 * four-argument overload; the result is still a complete discovery node, because the
 * infrastructure regions are the same either way. Since a node ignores regions it does
 * not serve, region sets need not match across a network.</p>
 *
 * <p><b>Bootstrap discovery.</b> A node can be told about one authenticated peer with
 * {@link #connect(AccountKey, InetSocketAddress)}. It pushes its own signed NodeInfo,
 * then pulls and merges the bootstrap node's {@code :p2p} and {@code :id} regions and
 * each currently desired complete social-owner slot. This lets existing and
 * late-joining nodes discover each other without accepting unrelated social owners.
 * On-chain bootstrap and public PoP selection remain to be built on top.</p>
 *
 * <p><b>Inbound policy.</b> The application-owned listener denies all network
 * lattice traffic until an operator assigns inbound connections to a propagator.
 * {@link #create} leaves that policy unset (deny-by-default); use
 * {@link #serveAllInbound()} for an intentionally public single-view node, or
 * configure the listener returned by {@link #getTransport()} before {@link #launch()}.
 * Assignment permits zero-trust public access but does not authenticate or upgrade the
 * connection. Complete social values still pass follow-aware DID admission before
 * persistence.</p>
 *
 * <p><b>Layering.</b> {@link NodeServer} is only the schema-independent
 * authoritative lattice host. This class separately owns its TCP transport and
 * P2P policy: {@link NodeDirectory}
 * publishes and interprets {@code [:p2p :nodes]}, {@link SocialReplicationPolicy}
 * selects social data, and {@link PointOfPresence} handles transient routed
 * messages. None of those application structures are interpreted by NodeServer.
 * P2PNode explicitly uses its node key as both transport identity and NodeInfo
 * owner; social DIDs and their signing keys remain independent.</p>
 */
public class P2PNode implements Closeable {

	private static final Logger log = LoggerFactory.getLogger(P2PNode.class.getName());

	private final NodeServer<Index<Keyword, ACell>> server;
	private final LatticePropagator propagator;
	private final LatticeListener transport;
	private final P2PApplication application;
	private final AKeyPair keyPair;
	private final DIDKeyAuthorizer didAuthorizer;
	private final SocialReplicationPolicy socialPolicy;
	private final NodeDirectory nodeDirectory;
	private final PointOfPresence pointOfPresence;
	private final ConcurrentHashMap<Convex,CompletableFuture<Void>> initialisations=
		new ConcurrentHashMap<>();
	/** Freezes P2P application configuration at the same boundary as NodeServer. */
	private boolean launchStarted;

	@SuppressWarnings("unchecked")
	private P2PNode(NodeServer<Index<Keyword, ACell>> server,
			LatticePropagator propagator,LatticeListener transport,NodeConfig config,AKeyPair keyPair,
			DIDKeyAuthorizer didAuthorizer) {
		this.server = server;
		this.propagator=propagator;
		this.transport=transport;
		this.application=P2PApplication.connect(server.getRootComponent());
		this.keyPair = keyPair;
		this.didAuthorizer=didAuthorizer;
		this.socialPolicy=new SocialReplicationPolicy(server,didAuthorizer);
		this.nodeDirectory=new NodeDirectory(server,propagator,transport,config,keyPair);
		this.pointOfPresence=new PointOfPresence(server,propagator,keyPair,nodeDirectory);
		propagator.setIngressFilter(socialPolicy::filterIngress);
		propagator.setPublicationFilter(value -> (Index<Keyword,ACell>)socialPolicy.filterPublication(
			new ACell[0],value));
		propagator.setApplicationMessageHandler(pointOfPresence::handle);
		propagator.setInboundLatticeListener(nodeDirectory::onAcceptedInbound);
	}

	/**
	 * Creates a P2P node serving the default region set
	 * ({@link P2PLattice#NODE_ROOT} — the P2P regions plus the bundled application
	 * regions). The node is not launched.
	 *
	 * @param store Store backing lattice persistence and acquisition
	 * @param config Node configuration, or null for defaults
	 * @param keyPair Signing key for this node's owned lattice data, or null for none
	 * @return A new (unlaunched) P2PNode
	 */
	public static P2PNode create(AStore store, NodeConfig config, AKeyPair keyPair) {
		return create(store,config,LatticePropagatorConfig.from(config),keyPair,
			P2PLattice.NODE_ROOT,DIDKeyAuthorizer.CONVEX);
	}

	/**
	 * Creates a P2P node with independently supplied host and propagation-group
	 * configuration.
	 *
	 * @param store authoritative node store
	 * @param nodeConfig standard-listener and persistence configuration, or {@code null}
	 * @param propagatorConfig route, queue and publication limits, or {@code null}
	 * @param keyPair node transport and NodeInfo key, or {@code null}
	 * @return unlaunched P2P node
	 */
	public static P2PNode create(AStore store,NodeConfig nodeConfig,
			LatticePropagatorConfig propagatorConfig,AKeyPair keyPair) {
		return create(store,nodeConfig,propagatorConfig,keyPair,
			P2PLattice.NODE_ROOT,DIDKeyAuthorizer.CONVEX);
	}

	/**
	 * Creates a P2P node serving an explicit region set.
	 *
	 * <p>This is how regions are switched off or added — not by running a different node
	 * server. Pass {@link P2PLattice#ROOT} for infrastructure only (a complete discovery
	 * node with the application regions off), or a root composed with
	 * {@code addLattice} to serve additional ones.</p>
	 *
	 * <p>A node ignores incoming values for regions it does not serve, so its region set
	 * need not match its peers'.</p>
	 *
	 * @param store Store backing lattice persistence and acquisition
	 * @param config Node configuration, or null for defaults
	 * @param keyPair Signing key for this node's owned lattice data, or null for none
	 * @param root Regions this node serves; must include the P2P regions
	 * @return A new (unlaunched) P2PNode
	 */
	public static P2PNode create(AStore store, NodeConfig config, AKeyPair keyPair,
			KeyedLattice root) {
		return create(store,config,LatticePropagatorConfig.from(config),keyPair,root,
			DIDKeyAuthorizer.CONVEX);
	}

	/** Creates a node with an authenticated DID resolution policy. */
	public static P2PNode create(AStore store, NodeConfig config, AKeyPair keyPair,
			KeyedLattice root,DIDKeyAuthorizer didAuthorizer) {
		return create(store,config,LatticePropagatorConfig.from(config),keyPair,root,
			didAuthorizer);
	}

	/**
	 * Creates a P2P node with explicit regions, independent host and propagation
	 * configuration, and an authenticated DID resolution policy.
	 *
	 * @param store authoritative node store
	 * @param nodeConfig standard-listener and persistence configuration, or {@code null}
	 * @param propagatorConfig route, queue and publication limits, or {@code null}
	 * @param keyPair node transport and NodeInfo key, or {@code null}
	 * @param root regions served by this node
	 * @param didAuthorizer DID-to-signing-key authorisation policy
	 * @return unlaunched P2P node
	 */
	public static P2PNode create(AStore store,NodeConfig nodeConfig,
			LatticePropagatorConfig propagatorConfig,AKeyPair keyPair,
			KeyedLattice root,DIDKeyAuthorizer didAuthorizer) {
		if (store == null) throw new IllegalArgumentException("Store must not be null");
		if (root == null) throw new IllegalArgumentException("Root lattice must not be null");
		if (didAuthorizer == null) throw new IllegalArgumentException("DID authorizer must not be null");

		NodeConfig effectiveConfig=(nodeConfig==null) ? NodeConfig.create() : nodeConfig;
		LatticePropagatorConfig effectivePropagatorConfig=(propagatorConfig==null)
			? LatticePropagatorConfig.create() : propagatorConfig;
		LatticeContext mergeContext=LatticeContext.create(
			null,keyPair,didAuthorizer::verifiesOwner);
		NodeServer<Index<Keyword, ACell>> server = new NodeServer<>(root,store,effectiveConfig);
		server.setMergeContext(mergeContext);
		LatticeConnectionManager manager=new LatticeConnectionManager(store);
		LatticePropagator propagator=new LatticePropagator(
			store,manager,root,value -> value,effectivePropagatorConfig);
		propagator.setMergeContext(mergeContext);
		propagator.setTransportKeyPair(keyPair);
		LatticeListener transport=new LatticeListener(effectiveConfig);
		transport.registerPropagator(propagator);
		P2PNode node=new P2PNode(server,propagator,transport,effectiveConfig,keyPair,didAuthorizer);
		server.addPropagator(propagator);
		return node;
	}

	/** Registers and opens this node's local {@code did:key} social user. */
	public Social social(AKeyPair userKeyPair) {
		if (userKeyPair==null) throw new IllegalArgumentException("User key pair must not be null");
		return social(DID.forKey(userKeyPair.getAccountKey()),userKeyPair);
	}

	/** Registers a local stable DID with one currently authorised signing key. */
	public Social social(AString did,AKeyPair userKeyPair) {
		if (userKeyPair==null) throw new IllegalArgumentException("User key pair must not be null");
		socialPolicy.addLocalUser(did,userKeyPair);
		return Social.connect(application,LatticeContext.create(
			null,userKeyPair,didAuthorizer::verifiesOwner));
	}

	/** Retains an additional social DID independently of local follow lists. */
	public void pinSocial(AString did) {
		socialPolicy.pin(did);
	}

	/**
	 * Gets a view of a P2P user's owned area of the lattice.
	 *
	 * <p>{@code node.p2p(userKey).cursor()} yields a cursor at that user's identity
	 * slot, already through the signing boundary — writes are signed with this node's
	 * key pair and {@code sync()} pushes them back into the lattice.
	 *
	 * <p>Any user key may be passed: another user's area is readable, but not writable
	 * (see {@link P2PUser} for why that fails at two independent layers).
	 *
	 * @param userKey The P2P user's account key
	 * @return View of that user's owned area
	 */
	public P2PUser p2p(AccountKey userKey) {
		return application.p2p(userKey);
	}

	/**
	 * Gets a view of this node's own P2P user area, using the key pair supplied at
	 * {@link #create}. This is the writable one.
	 *
	 * @return View of this node's own owned area
	 * @throws IllegalStateException if this node was created without a key pair
	 */
	public P2PUser p2p() {
		if (keyPair == null) {
			throw new IllegalStateException("Node has no key pair; use p2p(userKey) for a read-only view");
		}
		return p2p(keyPair.getAccountKey());
	}

	/**
	 * Grants every inbound network connection this application's public propagation
	 * group. This is appropriate only for an intentionally public node serving a
	 * single lattice view.
	 * The connection remains untrusted unless the independent node-key challenge
	 * succeeds, and the default social ingress filter remains in force.
	 * Must be called before {@link #launch()}.
	 *
	 * @return this node, for chaining
	 */
	public P2PNode serveAllInbound() {
		transport.setSelector(connection -> propagator);
		return this;
	}

	/**
	 * Declares the nodes this node connects to as Points of Presence. The signed
	 * NodeInfo advertisement lets other nodes route back through these PoPs even
	 * when this node has no public transport. Declarations do not open connections;
	 * call {@link #connect(AccountKey, InetSocketAddress)} after launch.
	 *
	 * <p>Must be called before {@link #launch()}.</p>
	 *
	 * @param popKeys unique remote PoP node keys
	 * @return this node, for chaining
	 */
	public synchronized P2PNode pointsOfPresence(AccountKey... popKeys) {
		requireNew("pointsOfPresence");
		AVector<AccountKey> pops=Vectors.empty();
		if (popKeys!=null) {
			for (AccountKey key:popKeys) pops=pops.conj(key);
		}
		nodeDirectory.setPointsOfPresence(pops);
		return this;
	}

	/**
	 * Advertises and enables bounded relay of valid end-to-end signed point
	 * messages over authenticated node routes. Direct delivery remains enabled on
	 * every node; only forwarding is opt-in. Must be called before launch.
	 *
	 * @return this node, for chaining
	 */
	public synchronized P2PNode relayMessages() {
		requireNew("relayMessages");
		nodeDirectory.setRelay(true);
		pointOfPresence.setRelay(true);
		return this;
	}

	/** Installs the non-blocking handler for point messages addressed to this node. */
	public void setMessageHandler(Consumer<ReceivedMessage> handler) {
		pointOfPresence.setMessageHandler(handler);
	}

	/**
	 * Sends a public end-to-end signed value towards a node.
	 *
	 * @return true when an authenticated first-hop route accepted the message;
	 *         this is not an end-to-end delivery receipt
	 */
	public boolean sendMessage(AccountKey destination,ACell value) {
		return pointOfPresence.send(destination,value,false);
	}

	/**
	 * Sends an end-to-end signed value whose body is ECIES-encrypted for the
	 * destination node key. Relays see routing metadata but not the value.
	 *
	 * @return true when an authenticated first-hop route accepted the message;
	 *         this is not an end-to-end delivery receipt
	 */
	public boolean sendPrivateMessage(AccountKey destination,ACell value) {
		return pointOfPresence.send(destination,value,true);
	}

	/** A successfully verified point message delivered to its destination node. */
	public record ReceivedMessage(Hash id,AccountKey sender,AccountKey destination,
		ACell value,boolean encrypted) {}

	/**
	 * Launches the authoritative node and application-owned TCP transport, installs
	 * P2P peer-initialisation policy, then publishes this node's signed NodeInfo using
	 * the actual bound port. Failure during transport launch or P2P publication closes
	 * every already-started component before the exception is returned.
	 *
	 * @throws IOException If an IO error occurs during launch
	 * @throws InterruptedException If the operation is interrupted
	 */
	public synchronized void launch() throws IOException, InterruptedException {
		nodeDirectory.validateLaunchConfiguration();
		requireNew("launch");
		launchStarted=true;
		var manager=propagator.getConnectionManager();
		// Admission is propagation policy, so the application installs it before
		// NodeServer starts the already-configured group.
		manager.setPeerAdmissionHandler((peerKey,peer) -> initialisePeer(peer)
			.exceptionally(error -> {
				log.debug("Unable to initialise admitted peer {}: {}",peerKey,error.getMessage());
				return null;
			}));
		server.launch();
		try {
			transport.launch();
			nodeDirectory.publishOwnRecord();
			for (Convex peer:manager.getPeers()) initialisePeer(peer);
			Integer port = transport.getPort();
			if (port != null && port >= 0) {
				log.info("Convex P2P node listening on port {}", port);
			} else {
				log.info("Convex P2P node started in local-only mode");
			}
		} catch (IOException | InterruptedException | RuntimeException | Error e) {
			transport.close();
			try {
				server.close();
			} catch (IOException closeFailure) {
				e.addSuppressed(closeFailure);
			}
			throw e;
		}
	}

	/** Rejects application configuration changes after the first launch begins. */
	private void requireNew(String operation) {
		if (launchStarted) {
			throw new IllegalStateException(operation+" is configuration-only and must precede launch");
		}
	}

	/**
	 * Adds one bootstrap peer and establishes an authenticated persistent
	 * connection to it.
	 *
	 * <p>After the remote endpoint proves {@code peerKey}, this node pushes only its
	 * own signed {@code [:p2p :nodes]} entry and waits for the merge acknowledgement.
	 * It then pulls and merges the remote node's infrastructure regions and currently
	 * desired complete social-owner slots. The remote
	 * node then challenges this same physical connection. Once the initiating node
	 * proves its signed NodeInfo key, the remote explicitly upgrades the inbound socket
	 * into an authenticated outbound propagation route. This permits bidirectional
	 * gossip even when this node publishes an empty {@code :transports} vector because
	 * it is behind NAT. A late joiner obtains only its follow-filtered social view.</p>
	 *
	 * @param peerKey expected AccountKey of the bootstrap node
	 * @param address bootstrap node's TCP address
	 * @return future completing after connection admission, own-identity merge and
	 *         local merge of the bootstrap node's selective bootstrap paths
	 */
	public CompletableFuture<Convex> connect(AccountKey peerKey, InetSocketAddress address) {
		if (!server.isRunning()) {
			return CompletableFuture.failedFuture(
				new IllegalStateException("P2P node must be launched before connecting"));
		}
		return propagator.getConnectionManager().connectPeer(peerKey,address)
			.thenCompose(peer -> initialisePeer(peer).thenApply(ignored -> peer));
	}

	private CompletableFuture<Void> initialisePeer(Convex peer) {
		return initialisations.computeIfAbsent(peer,p -> {
			CompletableFuture<Void> initialised=pushOwnNodeInfo(p)
				.thenCompose(ignored -> pullBootstrap(p));
			initialised.whenComplete((ignored,error) -> {
				if (error!=null) initialisations.remove(p,initialised);
			});
			return initialised;
		});
	}

	private CompletableFuture<Void> pullBootstrap(Convex peer) {
		return server.pullPath(propagator,peer,P2PLattice.KEY_P2P)
			.thenRun(nodeDirectory::refresh)
			.thenCompose(ignored -> server.pullPath(propagator,peer,P2PLattice.KEY_ID))
			.thenCompose(ignored -> pullDesiredSocial(peer));
	}

	private CompletableFuture<Void> pullDesiredSocial(Convex peer) {
		CompletableFuture<?>[] pulls=socialPolicy.desiredOwners().stream()
			.map(did -> server.pullPath(propagator,peer,Social.KEY_SOCIAL,did)
				.thenRun(() -> socialPolicy.cacheCurrentOwner(did)))
			.toArray(CompletableFuture[]::new);
		return CompletableFuture.allOf(pulls);
	}

	/**
	 * Returns a future for the current or next authenticated connection to a node.
	 * Useful when this node is expected to discover the peer from an incoming
	 * NodeInfo update.
	 *
	 * @param peerKey expected remote node key
	 * @return future completing with the admitted connection
	 */
	public CompletableFuture<Convex> whenConnected(AccountKey peerKey) {
		if (!server.isRunning()) {
			return CompletableFuture.failedFuture(
				new IllegalStateException("P2P node must be launched before awaiting a peer"));
		}
		return propagator.getConnectionManager().whenConnected(peerKey);
	}

	/**
	 * Waits until a physically inbound socket from {@code peerKey} has separately
	 * passed challenge/response and been upgraded for outbound propagation.
	 *
	 * <p>This is intentionally distinct from {@link #whenConnected(AccountKey)},
	 * which reports a manager-owned outbound client. An operator-assigned but
	 * untrusted inbound connection never completes this future.</p>
	 *
	 * @param peerKey expected remote node key
	 * @return future completing with the authenticated upgraded route
	 */
	public CompletableFuture<AConnection> whenInboundConnectionUpgraded(AccountKey peerKey) {
		if (!server.isRunning()) {
			return CompletableFuture.failedFuture(
				new IllegalStateException("P2P node must be launched before awaiting a route upgrade"));
		}
		return propagator.getConnectionManager()
			.whenInboundConnectionUpgraded(peerKey);
	}

	@SuppressWarnings({"unchecked", "rawtypes"})
	private CompletableFuture<Result> pushOwnNodeInfo(Convex peer) {
		if (keyPair==null) {
			return CompletableFuture.failedFuture(
				new IllegalStateException("Node has no signing key for its NodeInfo"));
		}

		ACell nodesValue=server.getCursor().get(P2PLattice.KEY_P2P,P2PLattice.KEY_NODES);
		if (!(nodesValue instanceof AHashMap<?,?> rawNodes)) {
			return CompletableFuture.failedFuture(new IllegalStateException(
				"Node has no published NodeInfo"));
		}
		AHashMap<ACell,SignedData<ACell>> nodes=(AHashMap) rawNodes;
		AccountKey ownKey=keyPair.getAccountKey();
		SignedData<ACell> signed=nodes.get(ownKey);
		if (signed==null) {
			return CompletableFuture.failedFuture(new IllegalStateException(
				"Node has no published NodeInfo"));
		}

		AHashMap<ACell,SignedData<ACell>> ownEntry=(AHashMap) Maps.of(ownKey,signed);
		var payload=Vectors.create(MessageTag.LATTICE_VALUE,
			Vectors.of(P2PLattice.KEY_P2P,P2PLattice.KEY_NODES),ownEntry);
		Message update=Message.create(MessageType.LATTICE_VALUE,payload);
		return peer.request(update).thenApply(result -> {
			if (result==null || result.isError()) {
				throw new CompletionException(new IOException(
					"Remote node rejected own NodeInfo update: "+result));
			}
			return result;
		});
	}

	/**
	 * Gets the cursor for this node's root lattice value.
	 *
	 * @return Root lattice cursor
	 */
	public ALatticeCursor<Index<Keyword, ACell>> getCursor() {
		return application.cursor();
	}

	/**
	 * Gets the root-level P2P application component.
	 *
	 * @return P2P application hosted by this node
	 */
	public P2PApplication getApplication() {
		return application;
	}

	/**
	 * Gets the underlying authoritative NodeServer for lattice and persistence access.
	 *
	 * @return The NodeServer for this node
	 */
	public NodeServer<Index<Keyword, ACell>> getNodeServer() {
		return server;
	}

	/**
	 * Gets this application's standard inbound TCP transport. Applications may
	 * register additional attached propagators and install a routing selector
	 * before launch.
	 *
	 * @return application-owned lattice listener
	 */
	public LatticeListener getTransport() {
		return transport;
	}

	/** Package test hook for this wrapper's application-owned propagation group. */
	LatticePropagator propagationGroup() {
		return propagator;
	}

	/**
	 * Gets the configured listener port before launch and the actual bound port
	 * afterwards. A negative value means no inbound socket is configured.
	 *
	 * @return configured or bound port, or {@code null} when unspecified
	 */
	public Integer getPort() {
		return transport.getPort();
	}

	/** Returns the bound inbound TCP address, or {@code null} when none is open. */
	public InetSocketAddress getHostAddress() {
		return transport.getHostAddress();
	}

	/**
	 * Checks whether this node is currently running.
	 *
	 * @return true if the node has been launched and not closed
	 */
	public boolean isRunning() {
		return server.isRunning() && transport.isRunning();
	}

	@Override
	public void close() throws IOException {
		transport.close();
		server.close();
	}

	/**
	 * Stub command line entry point. Launches a public P2P node and runs until the
	 * JVM is terminated.
	 *
	 * @param args Optional Etch store file path; a temporary store is used if absent
	 * @throws IOException If the store or node cannot be opened
	 * @throws InterruptedException If interrupted while running
	 */
	public static void main(String[] args) throws IOException, InterruptedException {
		AStore store = (args.length > 0)
			? EtchStore.create(new File(args[0]))
			: EtchStore.createTemp("convex-p2p");

		AKeyPair keyPair = AKeyPair.generate();
		log.info("Node key: {}", keyPair.getAccountKey().toChecksumHex());

		P2PNode node = P2PNode.create(store, NodeConfig.create(), keyPair).serveAllInbound();
		node.launch();

		// Stub: park until the JVM shuts down. A real node will run a control loop here.
		new CountDownLatch(1).await();
	}
}
