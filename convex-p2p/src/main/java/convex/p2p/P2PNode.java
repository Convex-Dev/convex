package convex.p2p;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.api.Convex;
import convex.core.Result;
import convex.core.crypto.AKeyPair;
import convex.core.data.ACell;
import convex.core.data.AHashMap;
import convex.core.data.AccountKey;
import convex.core.data.Index;
import convex.core.data.Keyword;
import convex.core.data.Maps;
import convex.core.data.SignedData;
import convex.core.data.Vectors;
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

/**
 * A Convex peer-to-peer lattice node.
 *
 * <p>This is the main entry point for convex-p2p — a rollup package, so this is the
 * <em>only</em> node server, with no separate social or per-application node. It composes
 * a region set with the {@link NodeServer} networking provided by convex-peer, giving a
 * node that can query, merge and propagate lattice values with other nodes over the
 * binary protocol.
 *
 * <p><b>Regions are configuration.</b> {@link #create(AStore, NodeConfig, AKeyPair)}
 * serves {@link P2PLattice#NODE_ROOT} — the P2P regions plus the bundled application
 * regions. To switch the application regions off, pass {@link P2PLattice#ROOT} to the
 * four-argument overload; the result is still a complete discovery node, because the
 * infrastructure regions are the same either way. Since a node ignores regions it does
 * not serve, region sets need not match across a network.
 *
 * <p><b>Bootstrap discovery.</b> A node can be told about one authenticated peer with
 * {@link #connect(AccountKey, InetSocketAddress)}. It pushes its own signed NodeInfo,
 * allowing the remote node to discover it and establish the reverse connection.
 * On-chain bootstrap, region subscription and bounded replication policy remain to
 * be built on top.
 *
 * <p><b>Inbound policy.</b> A NodeServer denies all network lattice traffic until an
 * operator assigns inbound connections to a propagator. {@link #create} leaves that
 * policy unset (deny-by-default); use {@link #serveAllInbound()} for an intentionally
 * public single-view node, or set a custom policy via
 * {@link NodeServer#setInboundPropagatorSelector} before {@link #launch()}.
 */
public class P2PNode implements Closeable {

	private static final Logger log = LoggerFactory.getLogger(P2PNode.class.getName());

	private final NodeServer<Index<Keyword, ACell>> server;
	private final P2PApplication application;
	private final AKeyPair keyPair;

	private P2PNode(NodeServer<Index<Keyword, ACell>> server, AKeyPair keyPair) {
		this.server = server;
		this.application=P2PApplication.connect(server.getRootComponent());
		this.keyPair = keyPair;
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
		return create(store, config, keyPair, P2PLattice.NODE_ROOT);
	}

	/**
	 * Creates a P2P node serving an explicit region set.
	 *
	 * <p>This is how regions are switched off or added — not by running a different node
	 * server. Pass {@link P2PLattice#ROOT} for infrastructure only (a complete discovery
	 * node with the application regions off), or a root composed with
	 * {@code addLattice} to serve additional ones.
	 *
	 * <p>A node ignores incoming values for regions it does not serve, so its region set
	 * need not match its peers'.
	 *
	 * @param store Store backing lattice persistence and acquisition
	 * @param config Node configuration, or null for defaults
	 * @param keyPair Signing key for this node's owned lattice data, or null for none
	 * @param root Regions this node serves; must include the P2P regions
	 * @return A new (unlaunched) P2PNode
	 */
	public static P2PNode create(AStore store, NodeConfig config, AKeyPair keyPair,
			KeyedLattice root) {
		if (store == null) throw new IllegalArgumentException("Store must not be null");
		if (root == null) throw new IllegalArgumentException("Root lattice must not be null");

		NodeServer<Index<Keyword, ACell>> server = new NodeServer<>(root, store, config);
		if (keyPair != null) {
			server.setMergeContext(LatticeContext.create(null, keyPair));
		}
		return new P2PNode(server, keyPair);
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
	 * Grants every inbound network connection the primary propagator view. Only
	 * appropriate for an intentionally public node serving a single lattice view.
	 * Must be called before {@link #launch()}.
	 *
	 * @return this node, for chaining
	 */
	public P2PNode serveAllInbound() {
		server.setInboundPropagatorSelector(connection -> server.getPropagator());
		return this;
	}

	/**
	 * Launches the node, binding its network listener and starting propagation.
	 *
	 * @throws IOException If an IO error occurs during launch
	 * @throws InterruptedException If the operation is interrupted
	 */
	public void launch() throws IOException, InterruptedException {
		server.launch();
		Integer port = server.getPort();
		if (port != null && port >= 0) {
			log.info("Convex P2P node listening on port {}", port);
		} else {
			log.info("Convex P2P node started in local-only mode");
		}
	}

	/**
	 * Adds one bootstrap peer and establishes an authenticated persistent
	 * connection to it.
	 *
	 * <p>After the remote endpoint proves {@code peerKey}, this node pushes only its
	 * own signed {@code [:p2p :nodes]} entry and waits for the merge acknowledgement.
	 * The remote node can then discover this node's advertised transport and open the
	 * reverse connection needed for bidirectional lattice gossip. The node must have
	 * published a NodeInfo record, normally through {@link NodeConfig#URL} or
	 * {@link NodeConfig#localNetwork()}.</p>
	 *
	 * @param peerKey expected AccountKey of the bootstrap node
	 * @param address bootstrap node's TCP address
	 * @return future completing after connection admission and own-identity merge
	 */
	public CompletableFuture<Convex> connect(AccountKey peerKey, InetSocketAddress address) {
		if (!server.isRunning()) {
			return CompletableFuture.failedFuture(
				new IllegalStateException("P2P node must be launched before connecting"));
		}
		return server.getPropagator().getConnectionManager().connectPeer(peerKey,address)
			.thenCompose(peer -> pushOwnNodeInfo(peer).thenApply(ignored -> peer));
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
		if (server.getPropagator()==null) {
			return CompletableFuture.failedFuture(
				new IllegalStateException("P2P node must be launched before awaiting a peer"));
		}
		return server.getPropagator().getConnectionManager().whenConnected(peerKey);
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
				"Node has no published NodeInfo; configure NodeConfig.URL"));
		}
		AHashMap<ACell,SignedData<ACell>> nodes=(AHashMap) rawNodes;
		AccountKey ownKey=keyPair.getAccountKey();
		SignedData<ACell> signed=nodes.get(ownKey);
		if (signed==null) {
			return CompletableFuture.failedFuture(new IllegalStateException(
				"Node has no published NodeInfo; configure NodeConfig.URL"));
		}

		AHashMap<ACell,SignedData<ACell>> ownEntry=(AHashMap) Maps.of(ownKey,signed);
		var payload=Vectors.create(MessageTag.LATTICE_VALUE,null,
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
	 * Gets the underlying NodeServer, for configuration and networking access.
	 *
	 * @return The NodeServer for this node
	 */
	public NodeServer<Index<Keyword, ACell>> getNodeServer() {
		return server;
	}

	/**
	 * Gets the port this node is listening on.
	 *
	 * @return Port number, or null if not launched
	 */
	public Integer getPort() {
		return server.getPort();
	}

	/**
	 * Checks whether this node is currently running.
	 *
	 * @return true if the node has been launched and not closed
	 */
	public boolean isRunning() {
		return server.isRunning();
	}

	@Override
	public void close() throws IOException {
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
