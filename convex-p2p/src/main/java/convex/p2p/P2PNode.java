package convex.p2p;

import java.io.Closeable;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.api.Convex;
import convex.api.ConvexRemote;
import convex.auth.did.DID;
import convex.auth.did.DIDKeyAuthorizer;
import convex.core.Result;
import convex.core.crypto.AKeyPair;
import convex.core.data.ACell;
import convex.core.data.AHashMap;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.AccountKey;
import convex.core.data.Blob;
import convex.core.data.Hash;
import convex.core.data.Index;
import convex.core.data.Keyword;
import convex.core.data.Maps;
import convex.core.data.SignedData;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import convex.core.message.AConnection;
import convex.core.message.Message;
import convex.core.message.MessageTag;
import convex.core.message.MessageType;
import convex.core.store.AStore;
import convex.core.util.ConfigUtils;
import convex.core.util.FileUtils;
import convex.core.util.Shutdown;
import convex.core.util.Utils;
import convex.etch.EtchStore;
import convex.lattice.LatticeHost;
import convex.lattice.cursor.AForkableCursor;
import convex.lattice.generic.KeyedLattice;
import convex.net.PeerName;
import convex.net.PeerResolver;
import convex.net.PeerTarget;
import convex.net.Transports;
import convex.node.LatticeConnectionManager;
import convex.node.LatticeListener;
import convex.node.LatticeNode;
import convex.node.LatticePropagator;
import convex.node.LatticePropagatorConfig;
import convex.node.NodeConfig;
import convex.node.NodeServer;
import convex.social.Social;

/**
 * A Convex peer-to-peer lattice node.
 *
 * <p>This is the main entry point for convex-p2p — a rollup package, so this is the
 * <em>only</em> node server, with no separate social or per-application node. It
 * assembles the node up to the lattice root: a host opened from the store as
 * configured, and a {@link LatticeNode} with one propagator group. From the root
 * down, the {@link P2PApplication} configured on the host owns the lattice and the
 * P2P policy.</p>
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
 * {@link #seed(List)} joins through seed nodes the same way and lets them go once
 * other peers can replace them. On-chain bootstrap and public PoP selection
 * remain to be built on top.</p>
 *
 * <p><b>Inbound policy.</b> The application-owned listener denies all network
 * lattice traffic until an operator assigns inbound connections to a propagator.
 * {@link #create} leaves that policy unset (deny-by-default); use
 * {@link #serveAllInbound()} for an intentionally public single-view node, or
 * configure the listener returned by {@link #getTransport()}.
 * Assignment permits zero-trust public access but does not authenticate or upgrade the
 * connection. Complete social values still pass follow-aware DID admission before
 * persistence.</p>
 *
 * <p><b>Layering.</b> {@link NodeServer} is the schema-independent replication layer
 * over the assembled host and merges nothing. The {@link P2PApplication} is the host's
 * receiver: it merges incoming values through its lattice, projected to what this node
 * wants, keeps the node records it wants with the validated node directory and lists
 * the paths to pull from a new peer. This class owns the transport and the moments
 * replication knows about: after the transport binds it asks the application to
 * publish the node's record, when a peer is admitted it pulls the application's
 * bootstrap paths, and when an inbound value is accepted it turns the application's
 * refreshed directory into connection candidates and authenticates a route the
 * application has validated. It tells the application's node registry which nodes it
 * routes to, which the operator named and which are its Points of Presence, so their
 * records are kept. {@link PointOfPresence}
 * handles transient routed messages and is not a lattice concern. P2PNode uses its
 * node key as both transport identity and NodeInfo owner; social DIDs and their
 * signing keys remain independent.</p>
 */
public class P2PNode implements Closeable {

	private static final Logger log = LoggerFactory.getLogger(P2PNode.class.getName());

	/**
	 * Node configuration key: the node records a node keeps besides pinned ones
	 * (Long, default 1024). The directory is not a connection list, so this is
	 * independent of the peers a node connects to.
	 */
	public static final AString MAX_NODE_RECORDS=Strings.intern("maxNodeRecords");

	/**
	 * Node configuration key: the seed nodes to join a network through, a list of at
	 * most {@link #MAX_SEEDS} entries, each a peer name as {@code url} with an optional
	 * expected {@code key}. Read with {@link #seeds(NodeConfig)} and added to the node's
	 * live list by {@link #seed(List)}.
	 */
	public static final AString SEEDS=Strings.intern("seeds");

	/** Most seed nodes a configuration may list, and a node's live list may hold. */
	public static final int MAX_SEEDS=Seeds.MAX_SEEDS;

	private static final AString SEED_KEY=Strings.intern("key");
	private static final AString SEED_URL=Strings.intern("url");

	/** Launcher configuration key: the Etch store file. Not a node setting. */
	public static final AString STORE=Strings.intern("store");

	/**
	 * Launcher configuration key: the node key's Ed25519 seed in hex, read with
	 * {@link #keyPair(ACell)}. Not a node setting, and kept out of the node's configuration.
	 */
	public static final AString KEYPAIR=Strings.intern("keypair");

	private final LatticeNode<Index<Keyword, ACell>> network;
	/** The network's server, which replicates the host. */
	private final NodeServer<Index<Keyword, ACell>> server;
	private final LatticePropagator propagator;
	private final P2PApplication application;
	private final NodeConfig config;
	private final AKeyPair keyPair;
	private final PointOfPresence pointOfPresence;
	private final ConcurrentHashMap<Convex,CompletableFuture<Void>> initialisations=
		new ConcurrentHashMap<>();
	/** Nodes the operator named, whose records are kept however full the registry is. */
	private final Set<AccountKey> named=ConcurrentHashMap.newKeySet();
	/** Completes when seeds are first released; null until seeding starts. Guarded by this node. */
	private CompletableFuture<Void> seeding;
	/** The seeding round in progress, or null between rounds. */
	private volatile SeedRound seedRound;
	/** Freezes P2P application configuration at the same boundary as NodeServer. */
	private boolean launchStarted;

	private P2PNode(LatticeNode<Index<Keyword, ACell>> network,P2PApplication application,
			LatticePropagator propagator,NodeConfig config,AKeyPair keyPair) {
		this.network=network;
		this.server=network.getServer();
		this.application=application;
		this.propagator=propagator;
		this.config=config;
		this.keyPair=keyPair;
		NodeRegistry registry=application.registry();
		NodeDirectory directory=application.directory();
		LatticeConnectionManager manager=propagator.getConnectionManager();
		registry.setMaxRecords(maxNodeRecords(config));
		// Named nodes, nodes with an authenticated route or one being authenticated, and
		// Points of Presence keep their records
		registry.setPinned(() -> {
			Set<AccountKey> pinned=new HashSet<>(named);
			pinned.addAll(manager.getAuthenticatedRouteKeys());
			pinned.addAll(propagator.getAuthenticatingInboundKeys());
			for (AccountKey pop:directory.pointsOfPresence()) pinned.add(pop);
			return pinned;
		});
		this.pointOfPresence=new PointOfPresence(server,propagator,keyPair,directory,application.blocklist());
		// The application merges incoming roots through its projection; the
		// propagator group publishes through the same projection.
		propagator.setPublicationFilter(application::project);
		propagator.setApplicationMessageHandler(pointOfPresence::handle);
		propagator.setInboundLatticeListener(this::onAcceptedInbound);
		// Names resolve against what this node knows, and never to a node or address it blocks
		network.setPeerResolver(new PeerResolver(PeerResolver.defaultDIDResolver(),this::knownTransports) {
			@Override
			public CompletableFuture<PeerTarget> resolve(PeerName name) {
				return super.resolve(name).thenApply(P2PNode.this::permitted);
			}
		});
	}

	/** The configured bound on node records kept besides pinned ones. */
	static int maxNodeRecords(NodeConfig config) {
		ACell value=config.getMap().get(MAX_NODE_RECORDS);
		if (value==null) return NodeRegistry.DEFAULT_MAX_RECORDS;
		CVMLong bound=RT.ensureLong(value);
		if (bound==null || bound.longValue()<0 || bound.longValue()>Integer.MAX_VALUE) {
			throw new IllegalArgumentException(MAX_NODE_RECORDS+" must be between 0 and "+Integer.MAX_VALUE+": "+value);
		}
		return (int)bound.longValue();
	}

	/**
	 * Reads the seed nodes a configuration lists under {@link #SEEDS}, for
	 * {@link #seed(List)}: each entry's {@code url} is a peer name, as
	 * {@link PeerResolver#parse(String, String)} reads it, with its optional {@code key}.
	 *
	 * @param config node configuration
	 * @return seed names in configured order, empty if none
	 * @throws IllegalArgumentException if the list or an entry is invalid
	 */
	public static List<PeerName> seeds(NodeConfig config) {
		List<PeerName> seeds=new ArrayList<>();
		ACell value=config.getMap().get(SEEDS);
		if (value==null) return seeds;
		if (!(value instanceof AVector<?> list)) {
			throw new IllegalArgumentException(SEEDS+" must be a list of {url, key} entries");
		}
		if (list.count()>MAX_SEEDS) {
			throw new IllegalArgumentException(SEEDS+" lists more than "+MAX_SEEDS+" nodes");
		}
		for (ACell entry:list) {
			AMap<ACell,ACell> seed=RT.ensureMap(entry);
			AString url=(seed==null)?null:RT.ensureString(seed.get(SEED_URL));
			if (url==null) throw new IllegalArgumentException("Seed needs a URL: "+entry);
			ACell key=seed.get(SEED_KEY);
			seeds.add(PeerResolver.parse(url.toString(),(key==null)?null:key.toString()));
		}
		return seeds;
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

		// Host opening is independent of transport configuration.
		LatticeHost<Index<Keyword,ACell>> host;
		try {
			host=LatticeHost.open(root.zero(),store);
		} catch (IOException e) {
			throw new UncheckedIOException("Unable to read the node's retained root",e);
		}
		// From the root down, the P2P application owns the lattice and its policy.
		P2PApplication application=P2PApplication.connect(root,host,
			keyPair==null?null:keyPair.getAccountKey(),
			keyPair==null?null:(owner,value) -> keyPair.signData(value),
			Utils::getCurrentTimestampCell,didAuthorizer);
		host.addApplication(application);

		// The node key is the transport identity of the group created next
		LatticeNode<Index<Keyword,ACell>> network=new LatticeNode<>(host,effectiveConfig);
		if (keyPair!=null) network.setIdentity(DID.forKey(keyPair.getAccountKey()),keyPair);
		LatticePropagator propagator=network.createPropagator(effectivePropagatorConfig);
		return new P2PNode(network,application,propagator,effectiveConfig,keyPair);
	}

	/** Registers and opens this node's local {@code did:key} social user. */
	public Social social(AKeyPair userKeyPair) {
		return application.social(userKeyPair);
	}

	/** Registers a local stable DID with one currently authorised signing key. */
	public Social social(AString did,AKeyPair userKeyPair) {
		return application.social(did,userKeyPair);
	}

	/** Retains an additional social DID independently of local follow lists. */
	public void pinSocial(AString did) {
		application.pinSocial(did);
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
	 * group, except one from an IP address this node has blocked, which is closed.
	 * This is appropriate only for an intentionally public node serving a single
	 * lattice view.
	 * The connection remains untrusted unless the independent node-key challenge
	 * succeeds, and the application still takes only the social owners it wants.
	 * May be called while running: connections accepted afterwards use the group,
	 * and established sockets keep their assignment, as
	 * {@link LatticeNode#serveAllInbound(LatticePropagator)} does.
	 *
	 * @return this node, for chaining
	 */
	public P2PNode serveAllInbound() {
		Blocklist blocklist=application.blocklist();
		network.getListener().setSelector(connection ->
			blocklist.refuses(connection.getRemoteAddress())?null:propagator);
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
		application.directory().setPointsOfPresence(pops);
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
		application.directory().setRelay(true);
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
		validateLaunchConfiguration();
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
		// The application's contact records hear what the manager learns about other nodes,
		// and a blocked node that authenticates is dropped then
		ContactRecords contacts=application.contacts();
		manager.setContactHandlers(this::contacted,(contacts==null)?null:contacts::failed);
		// A failed launch closes the network itself
		network.launch();
		try {
			// The transport knows its port now, so the application can publish the node's record.
			Integer port = getPort();
			application.directory().publishOwnRecord(config.getAdvertisedURL(port));
			refreshDiscovery();
			offerContacts();
			for (Convex peer:manager.getPeers()) initialisePeer(peer);
			if (port != null && port >= 0) {
				log.info("Convex P2P node listening on port {}", port);
			} else {
				log.info("Convex P2P node started in local-only mode");
			}
		} catch (RuntimeException | Error e) {
			try {
				network.close();
			} catch (IOException | RuntimeException | Error closeFailure) {
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

	/** Validates P2P advertisement settings before any resource is opened. */
	private void validateLaunchConfiguration() {
		AString configured=config.getURL();
		if (configured==null) return;
		String reason=NodeConfig.validatePublicURL(
			configured.toString(),config.isAllowPrivateURL());
		if (reason!=null) {
			throw new IllegalStateException("Invalid node URL configuration: "+reason);
		}
	}

	/**
	 * Replication accepted an inbound value. The application reads what it means for
	 * the node registry; this node acts on connections: refreshed discovery becomes
	 * connection candidates, and a validated one-record registry update may authenticate
	 * the inbound route that carried it. A connection claiming a blocked node is closed.
	 */
	private void onAcceptedInbound(AConnection connection,LatticePropagator source,
			ACell[] path,ACell value,boolean changed) {
		if (!NodeDirectory.mayAffectRegistry(path)) return;
		AccountKey claimed=application.directory().claimedOwner(path,value);
		if (connection!=null && claimed!=null && application.blocklist().isBlocked(claimed)) {
			connection.close();
			return;
		}
		if (connection!=null && claimed!=null) {
			source.authenticateInboundRoute(connection,claimed);
			// The claimant keeps its record while its route is authenticated, so a
			// record a full registry turned away is merged again
			var host=server.getHost();
			Index<Keyword,ACell> before=host.cursor().get();
			host.mergeAt(value,path);
			if (!before.equals(host.cursor().get())) host.sync();
		}
		refreshDiscovery();
	}

	/** Offers the application's refreshed directory as this node's connection candidates. */
	private void refreshDiscovery() {
		for (NodeDirectory.NodeRecord record:application.directory().refresh()) offer(record);
	}

	/** Offers a node as a connection candidate at the transports this node does not refuse; never a blocked node. */
	private void offer(NodeDirectory.NodeRecord record) {
		Blocklist blocklist=application.blocklist();
		if (blocklist.isBlocked(record.peerKey())) return;
		propagator.getConnectionManager().updateDiscoveredPeer(record.peerKey(),
			blocklist.dialable(record.transports()),record.timestamp());
	}

	/** Reports authenticated contact to the contact records, or drops a blocked node at once. */
	private void contacted(AccountKey peerKey) {
		if (application.blocklist().isBlocked(peerKey)) {
			drop(peerKey);
			return;
		}
		ContactRecords contacts=application.contacts();
		if (contacts!=null) contacts.contacted(peerKey,dialledAddress(peerKey));
	}

	/** The address of a live route this node dialled and proved the node's key over, or null. */
	private AString dialledAddress(AccountKey peerKey) {
		Convex outbound=propagator.getConnectionManager().getConnection(peerKey);
		if (!(outbound instanceof ConvexRemote remote) || remote.getEndpoint()==null
				|| !peerKey.equals(remote.getVerifiedPeer())) return null;
		return Strings.create(remote.getEndpoint().toString());
	}

	/**
	 * Offers each node this node has dialled before as a connection candidate at the
	 * address it found the node's key at. A node record names newer transports and
	 * replaces it.
	 */
	private void offerContacts() {
		ContactRecords contacts=application.contacts();
		if (contacts==null) return;
		for (Map.Entry<AccountKey,AHashMap<Keyword,ACell>> entry:contacts.all().entrySet()) offerContact(entry.getKey());
	}

	/** Offers a node as a connection candidate at the address this node found its key at, if any. */
	private void offerContact(AccountKey key) {
		ContactRecords contacts=application.contacts();
		AString address=(contacts==null)?null:ContactRecords.address(contacts.get(key));
		Blocklist blocklist=application.blocklist();
		if (address==null || blocklist.isBlocked(key)) return;
		propagator.getConnectionManager().updateDiscoveredPeer(key,
			blocklist.dialable(Vectors.<AString>of(address)),Long.MIN_VALUE);
	}

	/** Closes every connection to a node, including one it opened, and forgets it as a candidate. */
	private void drop(AccountKey peerKey) {
		LatticeConnectionManager manager=propagator.getConnectionManager();
		AConnection inbound=manager.getUpgradedInboundConnection(peerKey);
		manager.removePeer(peerKey);
		if (inbound!=null) inbound.close();
	}

	/** Whether this node refuses to dial a node at an address: the node, the address or its host is blocked. */
	private boolean refuses(AccountKey peerKey,InetSocketAddress address) {
		Blocklist blocklist=application.blocklist();
		return blocklist.isBlocked(peerKey)
			|| (address!=null && blocklist.refuses(Strings.create(Transports.endpoint(address).toString())));
	}

	/** Whether a node's live route is at an address this node refuses. */
	private boolean refusesRoute(LatticeConnectionManager manager,AccountKey peerKey) {
		Blocklist blocklist=application.blocklist();
		Convex outbound=manager.getConnection(peerKey);
		if (outbound!=null && blocklist.refuses(outbound.getHostAddress())) return true;
		if (outbound instanceof ConvexRemote remote && remote.getEndpoint()!=null
				&& blocklist.refuses(Strings.create(remote.getEndpoint().toString()))) return true;
		AConnection inbound=manager.getUpgradedInboundConnection(peerKey);
		return inbound!=null && blocklist.refuses(inbound.getRemoteAddress());
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
	 * <p>A blocked node, or one at a blocked address, is refused at once.</p>
	 *
	 * @param peerKey expected AccountKey of the bootstrap node
	 * @param address bootstrap node's TCP address
	 * @return future completing after connection admission, own-identity merge and
	 *         local merge of the bootstrap node's selective bootstrap paths, or failing
	 *         with a {@link SecurityException} if the node or address is blocked
	 */
	public CompletableFuture<Convex> connect(AccountKey peerKey, InetSocketAddress address) {
		if (!server.isRunning()) {
			return CompletableFuture.failedFuture(
				new IllegalStateException("P2P node must be launched before connecting"));
		}
		if (refuses(peerKey,address)) {
			return CompletableFuture.failedFuture(
				new SecurityException("Node "+peerKey+" at "+address+" is blocked"));
		}
		// A node the operator named keeps its record however full the registry is
		named.add(peerKey);
		return propagator.getConnectionManager().connectPeer(peerKey,address)
			.thenCompose(peer -> initialisePeer(peer).thenApply(ignored -> peer));
	}

	/**
	 * Connects to a peer the operator names, as {@link #connect(AccountKey, InetSocketAddress)}
	 * does, and keeps it as {@link LatticeNode#keepPeer} does: the name is resolved with
	 * this node's records and blocklist, and again before every reconnection.
	 *
	 * @param name peer name, as {@link PeerResolver#parse(String)} reads it
	 * @return future completing after admission, own-identity merge and local merge of
	 *         the peer's bootstrap paths, or failing if the name cannot be resolved, the
	 *         peer cannot be reached or it is blocked
	 */
	public CompletableFuture<Convex> connect(PeerName name) {
		if (!server.isRunning()) {
			return CompletableFuture.failedFuture(
				new IllegalStateException("P2P node must be launched before connecting"));
		}
		return network.connectPeer(propagator,name).thenCompose(peer -> {
			AccountKey key=peer.getVerifiedPeer();
			if (isBlocked(key)) {
				return CompletableFuture.<Convex>failedFuture(new SecurityException("Node "+key+" is blocked"));
			}
			// A node the operator named keeps its record however full the registry is
			named.add(key);
			network.keepPeer(propagator,name,peer);
			return initialisePeer(peer).thenApply(ignored -> peer);
		});
	}

	/**
	 * Adds seed names to this node's live list, as {@link #addSeed} does, and joins the
	 * network through the seeds listed, and again whenever this node is left with no
	 * route. Each time it has none, as when it starts, it reads the live list, resolves
	 * each seed's name with this node's records and blocklist and connects to it: once a
	 * seed proves its key, this node pushes its own record and pulls the seed's registry,
	 * which names other nodes to connect to. A seed that cannot be resolved or reached,
	 * fails its challenge or loses its route is resolved again and retried after a
	 * backoff, without holding up the others. A seed added while this node is seeding is
	 * connected to at once, and one removed is not retried.
	 *
	 * <p>Once this node's own ambient slots hold routes to other peers, they can replace
	 * the seeds, so every seed is released: the route this node opened to it closes, and
	 * it stays a candidate where this node's records place it. A seed so carries a node
	 * only while it joins. In a network too small to fill the slots, seeds stay
	 * connected. A seed also named with {@link #connect} stays connected. A blocked seed,
	 * one at a blocked address or one that turns out to be this node is skipped until the
	 * next round. Seeding stops when the node closes.</p>
	 *
	 * <p>A name with this node's own key is left out, so seed nodes may share one
	 * configuration that lists them all, and a name beyond {@link #MAX_SEEDS} is left out
	 * with a warning, so the list a node kept never stops it starting. Seeding starts
	 * once: a later call adds its names to the live list.</p>
	 *
	 * @param seeds seed names to add, as {@link #seeds(NodeConfig)} reads them
	 * @return future completing when the seeds are first released, at once if none is listed
	 */
	public synchronized CompletableFuture<Void> seed(List<PeerName> seeds) {
		if (!server.isRunning()) {
			return CompletableFuture.failedFuture(
				new IllegalStateException("P2P node must be launched before seeding"));
		}
		Seeds list=application.seeds();
		for (PeerName seed:seeds) {
			if (isOwn(seed)) continue;
			try {
				list.add(seed);
			} catch (IllegalStateException e) {
				log.warn("Seed {} left out: {}",seed,e.getMessage());
			}
		}
		if (!seeds.isEmpty()) application.syncHost();
		SeedRound round=seedRound;
		if (round!=null) for (PeerName seed:seeds) round.add(seed);
		if (seeding==null) {
			seeding=new CompletableFuture<>();
			seedWhenIsolated(seeding);
		}
		return list.all().isEmpty()?CompletableFuture.completedFuture(null):seeding;
	}

	/**
	 * Adds a seed to this node's live list, kept in its {@code :local} record so the
	 * seed survives a restart, replacing any seed at the same URI. A node that is seeding
	 * connects to it at once; otherwise it is used the next time this node seeds.
	 *
	 * @param seed seed name, as {@link PeerResolver#parse(String)} reads it
	 * @return whether no seed was listed at its URI before
	 * @throws IllegalArgumentException if it names this node's own key
	 * @throws IllegalStateException if {@link #MAX_SEEDS} seeds at other URIs are listed
	 */
	public boolean addSeed(PeerName seed) {
		if (seed==null) throw new IllegalArgumentException("Seed name must not be null");
		if (isOwn(seed)) throw new IllegalArgumentException("A node cannot seed from itself");
		boolean added=application.seeds().add(seed);
		application.syncHost();
		SeedRound round=seedRound;
		if (round!=null) round.add(seed);
		return added;
	}

	/**
	 * Removes the seed at a name's URI from this node's live list, whatever key it
	 * names. A seed not yet reached is not retried; one reached is released with the
	 * others when the round ends.
	 *
	 * @param seed seed name
	 * @return whether a seed was listed at its URI
	 */
	public boolean removeSeed(PeerName seed) {
		if (seed==null) throw new IllegalArgumentException("Seed name must not be null");
		boolean removed=application.seeds().remove(seed);
		application.syncHost();
		return removed;
	}

	/** The seeds in this node's live list. */
	public List<PeerName> getSeeds() {
		return application.seeds().all();
	}

	/** Whether a seed name says it is this node. */
	private boolean isOwn(PeerName seed) {
		return keyPair!=null && keyPair.getAccountKey().equals(seed.key());
	}

	/** Once this node has no route, connects to its seeds until other peers fill its ambient slots, then again. */
	private void seedWhenIsolated(CompletableFuture<Void> released) {
		LatticeConnectionManager manager=propagator.getConnectionManager();
		manager.whenIsolated().thenCompose(isolated -> {
			SeedRound round=new SeedRound();
			// Published before the list is read, so a seed added meanwhile reaches the round
			seedRound=round;
			List<PeerName> seeds=application.seeds().all();
			if (!seeds.isEmpty()) log.info("Joining the network through {} seed nodes",seeds.size());
			for (PeerName seed:seeds) round.add(seed);
			return manager.whenAmbientTargetMet().whenComplete((met,error) -> round.end());
		}).whenComplete((met,error) -> {
			// Fails only when the node closes
			if (error!=null) {
				released.completeExceptionally(error);
				return;
			}
			released.complete(null);
			seedWhenIsolated(released);
		});
	}

	/** One round of seeding: keeps each seed connected until the round ends, then releases the seeds reached. */
	private final class SeedRound {
		private final Set<AccountKey> reached=ConcurrentHashMap.newKeySet();
		/** Seeds this round has started on, so none is dialled twice at once. */
		private final Set<PeerName> started=ConcurrentHashMap.newKeySet();
		private volatile boolean over;

		/** Starts connecting to a seed, unless this round already has or it names this node. */
		void add(PeerName seed) {
			if (!isOwn(seed) && started.add(seed)) attempt(seed,0);
		}

		/**
		 * Resolves a seed and connects to it, again after a backoff if that fails or its
		 * route goes. A seed that is blocked, or is this node, is skipped until the next
		 * round; one no longer listed as it was is dropped.
		 */
		void attempt(PeerName seed,int failures) {
			if (over || !server.isRunning()) return;
			if (!application.seeds().contains(seed)) {
				started.remove(seed);
				return;
			}
			network.connectPeer(propagator,seed).whenComplete((peer,error) -> {
				Throwable cause=(error instanceof CompletionException && error.getCause()!=null)?error.getCause():error;
				AccountKey key=(error==null)?peer.getVerifiedPeer():null;
				if (cause instanceof SecurityException || cause instanceof IllegalArgumentException
						|| (key!=null && isBlocked(key))) {
					log.info("Skipping seed {}: {}",seed,(cause==null)?"blocked":cause.getMessage());
					return;
				}
				if (key==null) {
					log.debug("Seed {} not reached: {}",seed,(cause==null)?"no key proved":cause.getMessage());
					retry(seed,failures+1);
					return;
				}
				reached.add(key);
				// Admitted after the round ended, so released at once
				if (over) {
					releaseSeed(key);
					return;
				}
				propagator.getConnectionManager().whenDisconnected(key).thenRun(() -> {
					reached.remove(key);
					retry(seed,1);
				});
			});
		}

		private void retry(PeerName seed,int failures) {
			if (over) return;
			CompletableFuture.delayedExecutor(LatticeConnectionManager.calculateBackoff(failures),TimeUnit.MILLISECONDS)
				.execute(() -> attempt(seed,failures));
		}

		/** Ends the round and releases the seeds reached; an attempt still dialling releases its seed when admitted. */
		void end() {
			over=true;
			if (seedRound==this) seedRound=null;
			if (!reached.isEmpty()) log.info("Released seed nodes: other peers now fill this node's slots");
			for (AccountKey key:reached) releaseSeed(key);
		}
	}

	/** Releases a seed this node reached, unless the operator named it; it stays a candidate where this node knows it. */
	private void releaseSeed(AccountKey key) {
		if (named.contains(key)) return;
		propagator.getConnectionManager().releasePeer(key);
		NodeDirectory.NodeRecord record=application.directory().records().get(key);
		if (record!=null) offer(record);
		offerContact(key);
	}

	/** Transports this node knows for a node: its record's, else the address it found its key at. */
	private AVector<AString> knownTransports(AccountKey key) {
		NodeDirectory.NodeRecord record=application.directory().records().get(key);
		if (record!=null && record.transports().count()>0) return record.transports();
		ContactRecords contacts=application.contacts();
		AString address=(contacts==null)?null:ContactRecords.address(contacts.get(key));
		return (address==null)?null:Vectors.<AString>of(address);
	}

	/**
	 * A resolved peer without the addresses this node refuses.
	 *
	 * @throws SecurityException if this node blocks every key the peer may prove, or
	 *         refuses every address
	 */
	private PeerTarget permitted(PeerTarget target) {
		Blocklist blocklist=application.blocklist();
		AVector<AccountKey> keys=target.keys();
		if (!keys.isEmpty()) {
			AVector<AccountKey> allowed=Vectors.empty();
			for (AccountKey key:keys) {
				if (!blocklist.isBlocked(key)) allowed=allowed.conj(key);
			}
			if (allowed.isEmpty()) throw new SecurityException("Peer "+target.name()+" is blocked");
			keys=allowed;
		}
		AVector<AString> endpoints=blocklist.dialable(target.endpoints());
		if (endpoints.isEmpty()) throw new SecurityException("Every address of peer "+target.name()+" is blocked");
		return new PeerTarget(target.name(),keys,endpoints);
	}

	/**
	 * Blocks a node. Its record is dropped and refused, every connection to it is
	 * closed, including one it opened, and no message is taken from it, sent to it or
	 * relayed through it. It stops being a node the operator named. The blocklist is
	 * synced into this node's {@code :local} record, which the host keeps.
	 *
	 * @param node key of the node to block
	 * @return whether it was not blocked before
	 */
	public boolean block(AccountKey node) {
		if (node==null) throw new IllegalArgumentException("Node key must not be null");
		boolean added=application.block(node);
		named.remove(node);
		application.syncHost();
		drop(node);
		return added;
	}

	/**
	 * Blocks a transport address, such as {@code tcp://host:port}, or a host. This node
	 * dials no blocked address and no address at a blocked host, and takes no inbound
	 * connection from a blocked IP address. A node with a route at such an address, or
	 * advertising one, is dropped and offered again at its other addresses. A host name
	 * is matched as written, without DNS. The blocklist is synced as for
	 * {@link #block(AccountKey)}.
	 *
	 * @param address transport address or host
	 * @return whether it was not blocked before
	 * @throws IllegalArgumentException if the address is neither
	 */
	public boolean block(AString address) {
		Blocklist blocklist=application.blocklist();
		boolean added=blocklist.block(address);
		application.syncHost();
		LatticeConnectionManager manager=propagator.getConnectionManager();
		Map<AccountKey,NodeDirectory.NodeRecord> records=application.directory().records();
		Set<AccountKey> affected=new HashSet<>();
		for (NodeDirectory.NodeRecord record:records.values()) {
			if (blocklist.dialable(record.transports())!=record.transports()) affected.add(record.peerKey());
		}
		for (AccountKey key:manager.getAuthenticatedRouteKeys()) {
			if (refusesRoute(manager,key)) affected.add(key);
		}
		for (AccountKey key:affected) {
			drop(key);
			NodeDirectory.NodeRecord record=records.get(key);
			if (record!=null) offer(record);
		}
		return added;
	}

	/**
	 * Unblocks a node. Its record is taken again when it next arrives, making it a
	 * candidate again. The blocklist is synced as for {@link #block(AccountKey)}.
	 *
	 * @param node key of the node to unblock
	 * @return whether it was blocked
	 */
	public boolean unblock(AccountKey node) {
		boolean removed=application.blocklist().unblock(node);
		application.syncHost();
		return removed;
	}

	/**
	 * Unblocks a transport address or a host. A node that advertises addresses this
	 * node refused, and that it has no route to, is offered again with every address it
	 * no longer refuses; a node with a route, or one the operator named, keeps the
	 * addresses it has until its record changes. The blocklist is synced as for
	 * {@link #block(AccountKey)}.
	 *
	 * @param address transport address or host
	 * @return whether it was blocked
	 * @throws IllegalArgumentException if the address is neither
	 */
	public boolean unblock(AString address) {
		Blocklist blocklist=application.blocklist();
		List<NodeDirectory.NodeRecord> refused=new ArrayList<>();
		for (NodeDirectory.NodeRecord record:application.directory().records().values()) {
			if (blocklist.dialable(record.transports())!=record.transports()) refused.add(record);
		}
		boolean removed=blocklist.unblock(address);
		application.syncHost();
		LatticeConnectionManager manager=propagator.getConnectionManager();
		Set<AccountKey> routes=manager.getAuthenticatedRouteKeys();
		for (NodeDirectory.NodeRecord record:refused) {
			AccountKey key=record.peerKey();
			if (routes.contains(key) || named.contains(key)) continue;
			// The same record again is no newer, so the candidate is replaced
			manager.removePeer(key);
			offer(record);
		}
		return removed;
	}

	/** Whether the operator named a node, so its record is kept however full the registry is. */
	boolean isNamed(AccountKey node) {
		return named.contains(node);
	}

	/** Whether a node is blocked. */
	public boolean isBlocked(AccountKey node) {
		return application.blocklist().isBlocked(node);
	}

	/**
	 * Whether a transport address or a host is itself blocked: an address at a blocked
	 * host is refused but not itself blocked.
	 *
	 * @throws IllegalArgumentException if the address is neither
	 */
	public boolean isBlocked(AString address) {
		return application.blocklist().isBlocked(address);
	}

	private CompletableFuture<Void> initialisePeer(Convex peer) {
		initialisations.keySet().removeIf(p -> !p.isConnected());
		return initialisations.computeIfAbsent(peer,p -> {
			CompletableFuture<Void> initialised=propagator.getConnectionManager().withPeer(
				p.getVerifiedPeer(),false,() -> pushOwnNodeInfo(p)
					.thenCompose(ignored -> pullBootstrap(p)));
			initialised.whenComplete((ignored,error) -> {
				if (error!=null) initialisations.remove(p,initialised);
			});
			return initialised;
		});
	}

	/** Pulls the application's bootstrap paths from a new peer, in order, refreshing discovery after each. */
	private CompletableFuture<Void> pullBootstrap(Convex peer) {
		CompletableFuture<Void> chain=CompletableFuture.completedFuture(null);
		for (ACell[] path:application.bootstrapPaths()) {
			chain=chain.thenCompose(ignored -> server.pullPath(propagator,peer,true,path))
				.thenRun(this::refreshDiscovery);
		}
		return chain;
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

	private CompletableFuture<Result> pushOwnNodeInfo(Convex peer) {
		if (keyPair==null) {
			return CompletableFuture.failedFuture(
				new IllegalStateException("Node has no signing key for its NodeInfo"));
		}
		AHashMap<ACell,SignedData<ACell>> ownEntry=application.directory().ownEntry();
		if (ownEntry==null) {
			return CompletableFuture.failedFuture(new IllegalStateException(
				"Node has no published NodeInfo"));
		}
		var payload=Vectors.create(MessageTag.LATTICE_VALUE,NodeDirectory.REGISTRY_PATH,ownEntry);
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
	public AForkableCursor<Index<Keyword, ACell>> getCursor() {
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
		return network.getListener();
	}

	/** Package test hook for this wrapper's application-owned propagator group. */
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
		return network.getListener().getPort();
	}

	/** Returns the bound inbound TCP address, or {@code null} when none is open. */
	public InetSocketAddress getHostAddress() {
		return network.getHostAddress();
	}

	/**
	 * Checks whether this node is currently running.
	 *
	 * @return true if the node has been launched and not closed
	 */
	public boolean isRunning() {
		return server.isRunning() && network.getListener().isRunning();
	}

	/**
	 * Stops networking changing the host, as {@link LatticeNode#stopIngress()} does.
	 * Publication continues; see convex-peer/docs/SHUTDOWN.md for the service order.
	 */
	public void stopIngress() {
		network.stopIngress();
	}

	/**
	 * Stops networking, sending what the host has published to connected peers first.
	 * Neither publishes pending host edits nor flushes the borrowed store.
	 */
	@Override
	public void close() throws IOException {
		network.close();
	}

	/**
	 * Command line entry point. Launches a public P2P node, joins the network through
	 * any configured seeds and runs until the JVM is terminated.
	 *
	 * <p>The optional argument is a JSON5 file holding the node configuration, such as
	 * {@code port}, {@code url}, {@code maxNodeRecords} and {@code seeds}, plus
	 * {@code store}, the Etch store file, and {@code keypair}, the node key's Ed25519
	 * seed in hex. A store needs a stable key, since the node keeps its own record and
	 * its contact records under it. Without a store the node uses a temporary one, and
	 * without a key it generates one.</p>
	 *
	 * @param args Optional JSON5 configuration file
	 * @throws IOException If the configuration, store or node cannot be opened
	 * @throws InterruptedException If interrupted while running
	 */
	public static void main(String[] args) throws IOException, InterruptedException {
		AMap<AString,ACell> settings=(args.length>0)?ConfigUtils.readConfigFile(args[0]):Maps.empty();
		AString storeFile=RT.ensureString(settings.get(STORE));
		AKeyPair configuredKey=keyPair(settings.get(KEYPAIR));
		if (storeFile!=null && configuredKey==null) {
			throw new IllegalArgumentException("A store needs a stable node key: set "+KEYPAIR);
		}
		// The key stays out of the configuration given to the node
		NodeConfig config=NodeConfig.create(settings.dissoc(STORE).dissoc(KEYPAIR));
		// Invalid seeds fail before the store opens
		seeds(config);
		AKeyPair keyPair=(configuredKey!=null)?configuredKey:AKeyPair.generate();
		log.info("Node key: {}", keyPair.getAccountKey().toChecksumHex());

		AStore store = (storeFile!=null)
			? EtchStore.create(FileUtils.getFile(storeFile.toString()))
			: EtchStore.createTemp("convex-p2p");
		serve(store,config,keyPair,node -> {});
	}

	/**
	 * Runs a public node as a service until the JVM shuts down. Launches the node,
	 * adds the seeds the configuration lists to its live list and joins the network
	 * through that list, as {@link #seed(List)} does, then on shutdown stops it in the order
	 * convex-peer/docs/SHUTDOWN.md prescribes and closes the store.
	 *
	 * @param store store the node opens its host from; closed when the service ends
	 * @param config node configuration, including any {@link #SEEDS}
	 * @param keyPair node key
	 * @param started called once the node is launched, before the service waits
	 * @throws IOException If the node cannot be launched
	 * @throws InterruptedException If interrupted while running
	 * @throws IllegalArgumentException If the configuration is invalid
	 */
	public static void serve(AStore store,NodeConfig config,AKeyPair keyPair,Consumer<P2PNode> started)
			throws IOException, InterruptedException {
		List<PeerName> seeds;
		P2PNode node;
		try {
			seeds=seeds(config);
			node = P2PNode.create(store, config, keyPair).serveAllInbound();
		} catch (RuntimeException e) {
			store.close();
			throw e;
		}
		var host=node.getNodeServer().getHost();
		// Runs once; a second caller waits until it has finished
		Shutdown.Once shutdown=Shutdown.once(() -> {
			// Stop ingress and sync the application's local state, then close the host
			// (stops flushing, final checkpoint published through the node), then the
			// node (sends it to peers), then the store.
			try (store; node; host) {
				node.stopIngress();
				node.getApplication().sync();
			} catch (IOException | RuntimeException e) {
				log.warn("Unable to finish P2P service shutdown",e);
			}
		});
		try {
			host.autoFlush(Duration.ofSeconds(30)).failure()
				.thenAcceptAsync(error -> log.warn("P2P store flush failed",error));
			node.launch();
			host.checkpoint();
			node.seed(seeds);
			started.accept(node);
			// Runs in Convex's shutdown order, before transports and Etch files close
			Shutdown.addHook(Shutdown.SERVICE,shutdown);
			shutdown.await();
		} finally {
			Shutdown.removeHook(Shutdown.SERVICE,shutdown);
			shutdown.run();
		}
	}

	/**
	 * Reads a launcher's node key, as {@link #KEYPAIR} holds it.
	 *
	 * @param seed Ed25519 seed in hex, or null
	 * @return the key pair, or null if no seed is set
	 * @throws IllegalArgumentException if the seed is not a valid Ed25519 seed
	 */
	public static AKeyPair keyPair(ACell seed) {
		if (seed==null) return null;
		AString hex=RT.ensureString(seed);
		Blob bytes=(hex==null)?null:Blob.parse(hex.toString());
		if (bytes==null || bytes.count()!=AKeyPair.SEED_LENGTH) {
			throw new IllegalArgumentException(KEYPAIR+" must be a "+AKeyPair.SEED_LENGTH+"-byte Ed25519 seed in hex");
		}
		return AKeyPair.create(bytes);
	}
}
