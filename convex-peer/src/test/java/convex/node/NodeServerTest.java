package convex.node;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import convex.core.ErrorCodes;
import convex.core.Result;
import convex.core.cpos.CPoSConstants;
import convex.core.crypto.AKeyPair;
import convex.core.cvm.Address;
import convex.core.cvm.transactions.ATransaction;
import convex.core.data.AccountKey;
import convex.core.data.ACell;
import convex.core.data.AHashMap;
import convex.core.data.AVector;
import convex.api.Convex;
import convex.api.ConvexRemote;
import convex.core.data.ASet;
import convex.core.data.AString;
import convex.core.data.Blob;
import convex.core.data.Blobs;
import convex.core.data.Cells;
import convex.core.data.Hash;
import convex.core.data.Index;
import convex.core.data.Keyword;
import convex.core.data.Maps;
import convex.core.data.Sets;
import convex.core.data.SignedData;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.AInteger;
import convex.core.data.prim.CVMLong;
import convex.core.data.Ref;
import convex.core.message.AConnection;
import convex.core.message.Message;
import convex.core.message.MessageTag;
import convex.core.message.MessageType;
import convex.core.exceptions.StoreException;
import convex.core.store.AStore;
import convex.core.store.MemoryStore;
import convex.lattice.ALattice;
import convex.lattice.Lattice;
import convex.lattice.LatticeContext;
import convex.lattice.cursor.ACursor;
import convex.lattice.generic.KeyedLattice;
import convex.lattice.generic.MaxLattice;
import convex.lattice.generic.SetLattice;
import convex.net.impl.netty.NettyServer;

/**
 * Tests for NodeServer class.
 * 
 * Basic smoke tests for creating and operating a local NodeServer instance.
 */
public class NodeServerTest {

	private NodeServer<AInteger> maxNodeServer;
	private NodeServer<ASet<ACell>> setNodeServer;
	private AStore store;

	@BeforeEach
	public void setUp() {
		store = new MemoryStore();
	}

	@AfterEach
	public void tearDown() throws IOException {
		if (maxNodeServer != null) {
			maxNodeServer.close();
		}
		if (setNodeServer != null) {
			setNodeServer.close();
		}
		if (store != null) {
			store.close();
		}
	}

	/** Attaches the complete identity-view group used by single-policy tests. */
	private static <V extends ACell> LatticePropagator propagator(NodeServer<V> node) {
		List<LatticePropagator> existing=node.getPropagators();
		if (!existing.isEmpty()) return existing.get(0);
		LatticePropagator result=unattachedPropagator(node);
		node.addPropagator(result);
		return result;
	}

	/** Creates the identity-view group so a test can configure policy before attachment. */
	private static <V extends ACell> LatticePropagator unattachedPropagator(
			NodeServer<V> node) {
		LatticePropagator result=new LatticePropagator(
			node.getStore(),node.getLattice(),value -> value,node.getConfig());
		result.setMergeContext(node.getCursor().getContext());
		return result;
	}

	/** Explicit public test policy: every network connection uses one configured view. */
	private static void allowSingleGroupInbound(NodeServer<?> node) {
		LatticePropagator propagator=propagator(node);
		node.setInboundPropagatorSelector(connection -> propagator);
	}

	/** Syncs the authoritative root, then waits for the separately owned view. */
	private static void syncAndAwaitPropagator(NodeServer<?> node) throws Exception {
		CompletableFuture<ACell> announced=propagator(node).nextAnnounce();
		node.getCursor().sync();
		announced.get(5,TimeUnit.SECONDS);
	}

	/**
	 * Test creating a NodeServer with MaxLattice
	 */
	@Test
	public void testCreateMaxLatticeServer() {
		ALattice<AInteger> lattice = MaxLattice.create();
		maxNodeServer = new NodeServer<>(lattice, store);

		assertNotNull(maxNodeServer);
		assertNotNull(maxNodeServer.getLattice());
		assertNotNull(maxNodeServer.getStore());
		assertNotNull(maxNodeServer.getCursor());
		assertFalse(maxNodeServer.isRunning());
	}

	/**
	 * Test creating a NodeServer with SetLattice
	 */
	@Test
	public void testCreateSetLatticeServer() {
		ALattice<ASet<ACell>> lattice = SetLattice.create();
		setNodeServer = new NodeServer<>(lattice, store);

		assertNotNull(setNodeServer);
		assertNotNull(setNodeServer.getLattice());
		assertNotNull(setNodeServer.getStore());
		assertNotNull(setNodeServer.getCursor());
		assertFalse(setNodeServer.isRunning());
	}

	/** A local host needs no implicit/default propagation policy group. */
	@Test
	public void testLaunchWithoutPropagatorsPersistsLocally() throws Exception {
		maxNodeServer=new NodeServer<>(MaxLattice.create(),store,NodeConfig.port(-1));
		assertTrue(maxNodeServer.getPropagators().isEmpty());

		maxNodeServer.launch();
		maxNodeServer.getCursor().set(CVMLong.create(17));
		maxNodeServer.getCursor().sync();

		assertEquals(CVMLong.create(17),store.getRootData());
		assertTrue(maxNodeServer.isRunning());
	}

	/** Attachment consumes a complete application policy; it never becomes a node setter. */
	@Test
	public void testPropagatorPolicyMustPrecedeAttachment() {
		maxNodeServer=new NodeServer<>(MaxLattice.create(),store,NodeConfig.port(-1));
		LatticePropagator group=unattachedPropagator(maxNodeServer);
		group.setTransportKeyPair(AKeyPair.generate());
		group.setIngressFilter((path,value) -> value);
		group.setPublicationFilter(value -> value);
		group.setApplicationMessageHandler(message -> false);
		group.setInboundLatticeListener((connection,owner,path,value,changed) -> {});

		maxNodeServer.addPropagator(group);
		assertSame(group,maxNodeServer.getPropagators().get(0));
		assertThrows(IllegalStateException.class,
			() -> group.setMergeContext(LatticeContext.EMPTY));
		assertThrows(IllegalStateException.class,
			() -> group.setTransportKeyPair(AKeyPair.generate()));
		assertThrows(IllegalStateException.class,
			() -> group.setIngressFilter((path,value) -> value));
		assertThrows(IllegalStateException.class,
			() -> group.setPublicationFilter(value -> value));
		assertThrows(IllegalStateException.class,
			() -> group.setApplicationMessageHandler(message -> true));
		assertThrows(IllegalStateException.class,
			() -> group.setInboundLatticeListener(null));
	}

	/** Attachment transfers lifecycle ownership even if the host never launches. */
	@Test
	public void testCloseBeforeLaunchClosesAttachedPropagator() throws IOException {
		maxNodeServer=new NodeServer<>(MaxLattice.create(),store,NodeConfig.port(-1));
		CloseTrackingPropagator group=new CloseTrackingPropagator(store);
		maxNodeServer.addPropagator(group);

		maxNodeServer.close();

		assertTrue(group.closed);
		assertEquals(NodeServer.LifecycleState.STOPPED,maxNodeServer.getLifecycleState());
	}

	/** Every propagation-group callback is optional, isolated fan-out. */
	@Test
	public void testPropagatorFailuresCannotBreakAuthoritativeLoop() throws Exception {
		maxNodeServer=new NodeServer<>(MaxLattice.create(),store,NodeConfig.port(-1));
		FailingPropagator broken=new FailingPropagator(store);
		LatticePropagator healthy=new LatticePropagator(
			store,MaxLattice.create(),value -> value,NodeConfig.port(-1));
		maxNodeServer.addPropagator(broken);
		maxNodeServer.addPropagator(healthy);

		maxNodeServer.launch();
		assertTrue(maxNodeServer.isRunning(),"a failed propagation group must not fail launch");
		CompletableFuture<ACell> announced=healthy.nextAnnounce();
		maxNodeServer.getCursor().set(CVMLong.create(23));
		maxNodeServer.getCursor().sync();

		assertEquals(CVMLong.create(23),store.getRootData());
		assertEquals(CVMLong.create(23),announced.get(5,TimeUnit.SECONDS));
		maxNodeServer.close();
		assertEquals(NodeServer.LifecycleState.STOPPED,maxNodeServer.getLifecycleState());
	}

	private static final class FailingPropagator extends LatticePropagator {
		FailingPropagator(AStore store) {
			super(store,MaxLattice.create(),value -> value,NodeConfig.port(-1));
		}

		@Override public ACell processSnapshot(ACell value) {
			throw new IllegalStateException("simulated materialisation failure");
		}

		@Override public synchronized void start() {
			throw new IllegalStateException("simulated launch failure");
		}

		@Override public void triggerBroadcast(ACell value) {
			throw new IllegalStateException("simulated notification failure");
		}

		@Override public void stopIngress() throws IOException {
			throw new IOException("simulated ingress shutdown failure");
		}

		@Override public void triggerAndClose(ACell finalValue) {
			throw new IllegalStateException("simulated publication shutdown failure");
		}
	}

	private static final class CloseTrackingPropagator extends LatticePropagator {
		boolean closed;

		CloseTrackingPropagator(AStore store) {
			super(store,MaxLattice.create(),value -> value,NodeConfig.port(-1));
		}

		@Override public void close() {
			closed=true;
			super.close();
		}
	}

	/**
	 * Test initial value is lattice zero
	 */
	@Test
	public void testInitialValue() {
		ALattice<AInteger> lattice = MaxLattice.create();
		maxNodeServer = new NodeServer<>(lattice, store);

		AInteger initialValue = maxNodeServer.getLocalValue();
		assertNotNull(initialValue);
		assertEquals(CVMLong.ZERO, initialValue);
		assertEquals(lattice.zero(), initialValue);
	}

	/**
	 * Test value cursor operations
	 */
	@Test
	public void testValueCursor() {
		ALattice<AInteger> lattice = MaxLattice.create();
		maxNodeServer = new NodeServer<>(lattice, store);

		ACursor<AInteger> cursor = maxNodeServer.getCursor();
		assertNotNull(cursor);

		// Initial value should be zero
		assertEquals(CVMLong.ZERO, cursor.get());

		// Set a new value
		cursor.set(CVMLong.ONE);
		assertEquals(CVMLong.ONE, cursor.get());
		assertEquals(CVMLong.ONE, maxNodeServer.getLocalValue());

		// Update using getAndSet
		AInteger oldValue = cursor.getAndSet(CVMLong.TWO);
		assertEquals(CVMLong.ONE, oldValue);
		assertEquals(CVMLong.TWO, cursor.get());
	}

	/**
	 * Test mergeValue method with MaxLattice
	 */
	@Test
	public void testMergeValue() {
		ALattice<AInteger> lattice = MaxLattice.create();
		maxNodeServer = new NodeServer<>(lattice, store);

		// Start with zero
		assertEquals(CVMLong.ZERO, maxNodeServer.getLocalValue());

		// Merge with a value using the public mergeValue method
		AInteger merged = maxNodeServer.mergeValue(CVMLong.ONE);
		assertNotNull(merged);
		assertEquals(CVMLong.ONE, merged);
		assertEquals(CVMLong.ONE, maxNodeServer.getLocalValue());

		// Merge with larger value
		merged = maxNodeServer.mergeValue(CVMLong.create(5));
		assertNotNull(merged);
		assertEquals(CVMLong.create(5), merged);
		assertEquals(CVMLong.create(5), maxNodeServer.getLocalValue());

		// Merge with smaller value (should keep larger)
		merged = maxNodeServer.mergeValue(CVMLong.create(3));
		assertNotNull(merged);
		// Max lattice should keep the maximum value
		assertEquals(CVMLong.create(5), merged);
		assertEquals(CVMLong.create(5), maxNodeServer.getLocalValue());
		
		// Test merging null value (should return null)
		AInteger nullResult = maxNodeServer.mergeValue(null);
		assertEquals(null, nullResult);
	}

	/**
	 * #564: inbound values over the configured size limit are rejected before merge.
	 */
	@Test
	public void testInboundValueSizeLimit() {
		ALattice<AInteger> lattice = MaxLattice.create();

		// A node configured with a tight inbound size limit (100 bytes)
		NodeConfig tight = NodeConfig.create(Maps.of(NodeConfig.MAX_INBOUND_VALUE_SIZE, CVMLong.create(100)));
		maxNodeServer = new NodeServer<>(lattice, store, tight);

		// A small (embedded) value is within the limit
		assertTrue(propagator(maxNodeServer).withinInboundSizeLimit(CVMLong.ONE));

		// A large value exceeds the limit and is rejected
		convex.core.data.ABlob big = convex.core.data.Blob.wrap(new byte[500]);
		assertTrue(big.getMemorySize() > 100); // sanity: this value really is over the limit
		assertFalse(propagator(maxNodeServer).withinInboundSizeLimit(big));

		// Under the default (permissive) config, the same large value is accepted
		NodeServer<AInteger> permissive = new NodeServer<>(lattice, store);
		try {
			assertTrue(propagator(permissive).withinInboundSizeLimit(big));
		} finally {
			try { permissive.close(); } catch (Exception e) { /* ignore */ }
		}
	}

	/**
	 * #562: an inbound value of the wrong type for the target lattice is rejected cleanly
	 * and leaves the cursor unchanged (the merge aborts atomically).
	 */
	@Test
	public void testWrongTypeMergeRejected() {
		ALattice<AInteger> lattice = MaxLattice.create();
		maxNodeServer = new NodeServer<>(lattice, store);
		var c = maxNodeServer.getCursor();
		c.set(CVMLong.create(5));

		// A correct-type value merges (MaxLattice keeps the larger)
		assertTrue(maxNodeServer.mergeIncoming(c, CVMLong.create(7)));
		assertEquals(CVMLong.create(7), c.get());

		// A wrong-type value is rejected; the cursor is unchanged
		assertFalse(maxNodeServer.mergeIncoming(c, convex.core.data.Blob.wrap(new byte[8])));
		assertEquals(CVMLong.create(7), c.get());
	}

	/** A lattice whose merge throws an Error (e.g. simulating deep-recursion StackOverflowError). */
	static final class ThrowingLattice extends ALattice<AInteger> {
		@Override public AInteger merge(AInteger ownValue, AInteger otherValue) {
			throw new StackOverflowError("simulated deep-recursion merge");
		}
		@Override public AInteger zero() { return null; }
		@Override public boolean checkForeign(AInteger value) { return true; }
		@Override public <T extends ACell> ALattice<T> path(ACell childKey) { return null; }
	}

	/** A lattice modelling a fatal JVM failure rather than malformed recursion. */
	static final class FatalThrowingLattice extends ALattice<AInteger> {
		@Override public AInteger merge(AInteger ownValue, AInteger otherValue) {
			throw new OutOfMemoryError("simulated fatal merge failure");
		}
		@Override public AInteger zero() { return null; }
		@Override public boolean checkForeign(AInteger value) { return true; }
		@Override public <T extends ACell> ALattice<T> path(ACell childKey) { return null; }
	}

	@Test
	public void testProductionInboundDefaultsAndOverrides() {
		NodeConfig defaults = NodeConfig.create();
		assertEquals(4 * 1024 * 1024, defaults.getMaxMessageSize());
		assertEquals(defaults.getMaxMessageSize(), defaults.getMaxDeltaMessageSize());
		assertEquals(16 * 1024 * 1024, defaults.getMaxDeltaBroadcastSize());
		assertEquals(convex.core.cpos.CPoSConstants.MAX_MESSAGE_LENGTH,
			defaults.getMaxTrustedMessageSize());
		assertEquals(defaults.getMaxMessageSize(), defaults.getMaxInboundValueSize());
		assertEquals(256, defaults.getMaxConnections());
		assertEquals(1024, defaults.getInboundQueueSize());
		assertEquals(16 * 1024 * 1024, defaults.getMaxInboundQueueBytes());
		assertEquals(10_000L, defaults.getInboundShutdownTimeout());
		assertEquals(30_000L, defaults.getMaxFutureTimestampSkew());

		NodeConfig configured = NodeConfig.create(Maps.of(
			NodeConfig.MAX_MESSAGE_SIZE, CVMLong.create(8192),
			NodeConfig.MAX_DELTA_MESSAGE_SIZE, CVMLong.create(16384),
			NodeConfig.MAX_DELTA_BROADCAST_SIZE, CVMLong.create(32768),
			NodeConfig.MAX_TRUSTED_MESSAGE_SIZE, CVMLong.create(65536),
			NodeConfig.MAX_CONNECTIONS, CVMLong.create(12),
			NodeConfig.INBOUND_QUEUE_SIZE, CVMLong.create(34),
			NodeConfig.MAX_INBOUND_QUEUE_BYTES, CVMLong.create(65536),
			NodeConfig.INBOUND_SHUTDOWN_TIMEOUT, CVMLong.create(56),
			NodeConfig.MAX_FUTURE_TIMESTAMP_SKEW, CVMLong.create(78)));
		assertEquals(8192, configured.getMaxMessageSize());
		assertEquals(16384, configured.getMaxDeltaMessageSize());
		assertEquals(32768, configured.getMaxDeltaBroadcastSize());
		assertEquals(65536, configured.getMaxTrustedMessageSize());
		assertEquals(12, configured.getMaxConnections());
		assertEquals(34, configured.getInboundQueueSize());
		assertEquals(65536, configured.getMaxInboundQueueBytes());
		assertEquals(56, configured.getInboundShutdownTimeout());
		assertEquals(78, configured.getMaxFutureTimestampSkew());

		NodeConfig invalid = NodeConfig.create(Maps.of(
			NodeConfig.INBOUND_QUEUE_SIZE, CVMLong.ZERO));
		assertThrows(IllegalArgumentException.class, invalid::getInboundQueueSize);
		NodeConfig invalidTimeout = NodeConfig.create(Maps.of(
			NodeConfig.INBOUND_SHUTDOWN_TIMEOUT, CVMLong.ZERO));
		assertThrows(IllegalArgumentException.class, invalidTimeout::getInboundShutdownTimeout);
		NodeConfig invalidSkew = NodeConfig.create(Maps.of(
			NodeConfig.MAX_FUTURE_TIMESTAMP_SKEW, CVMLong.create(-1)));
		assertThrows(IllegalArgumentException.class, invalidSkew::getMaxFutureTimestampSkew);
		NodeConfig invalidDeltaBudget=NodeConfig.create(Maps.of(
			NodeConfig.MAX_DELTA_MESSAGE_SIZE,CVMLong.create(4096),
			NodeConfig.MAX_DELTA_BROADCAST_SIZE,CVMLong.create(2048)));
		assertThrows(IllegalArgumentException.class,invalidDeltaBudget::getMaxDeltaBroadcastSize);
		NodeConfig invalidInboundBytes=NodeConfig.create(Maps.of(
			NodeConfig.MAX_MESSAGE_SIZE,CVMLong.create(4096),
			NodeConfig.MAX_INBOUND_QUEUE_BYTES,CVMLong.create(2048)));
		assertThrows(IllegalArgumentException.class,invalidInboundBytes::getMaxInboundQueueBytes);
		assertThrows(IllegalArgumentException.class,
			() -> new LatticePropagator(
				store,MaxLattice.create(),value -> value,invalidTimeout),
			"invalid endpoint policy must be rejected while the application configures the group");

		NodeConfig aboveProtocolMaximum = NodeConfig.create(Maps.of(
			NodeConfig.MAX_TRUSTED_MESSAGE_SIZE,
			CVMLong.create(convex.core.cpos.CPoSConstants.MAX_MESSAGE_LENGTH + 1)));
		assertThrows(IllegalArgumentException.class, aboveProtocolMaximum::getMaxTrustedMessageSize);
	}

	/** Records the thread used for a real MaxLattice merge. */
	static final class RecordingLattice extends ALattice<AInteger> {
		private final ALattice<AInteger> delegate = MaxLattice.create();
		final Set<String> mergeThreads=ConcurrentHashMap.newKeySet();

		@Override public AInteger merge(AInteger ownValue, AInteger otherValue) {
			mergeThreads.add(Thread.currentThread().getName());
			return delegate.merge(ownValue, otherValue);
		}
		@Override public AInteger zero() { return delegate.zero(); }
		@Override public boolean checkForeign(AInteger value) { return delegate.checkForeign(value); }
		@Override public <T extends ACell> ALattice<T> path(ACell childKey) { return delegate.path(childKey); }
	}

	/** Memory store that counts durable root updates. */
	static final class CountingMemoryStore extends MemoryStore {
		final AtomicInteger rootWrites = new AtomicInteger();

		@Override public <T extends ACell> Ref<T> setRootData(T data) {
			rootWrites.incrementAndGet();
			return super.setRootData(data);
		}
	}

	/** Memory store with a switchable fundamental root-write failure. */
	static final class FailingMemoryStore extends MemoryStore {
		volatile boolean failRootWrites;
		volatile int failOnRootWrite = -1;
		final AtomicInteger rootWrites = new AtomicInteger();

		@Override public <T extends ACell> Ref<T> setRootData(T data) {
			int writeNumber = rootWrites.incrementAndGet();
			if (failRootWrites || writeNumber == failOnRootWrite) {
				throw new StoreException("simulated primary-store failure");
			}
			return super.setRootData(data);
		}
	}

	/** Store gate used to hold launch at its first root checkpoint deterministically. */
	static final class BlockingRootStore extends MemoryStore {
		final CountDownLatch rootWriteEntered = new CountDownLatch(1);
		final CountDownLatch releaseRootWrite = new CountDownLatch(1);
		volatile boolean blockRootWrite = true;

		@Override
		public <T extends ACell> Ref<T> setRootData(T data) {
			if (blockRootWrite) {
				rootWriteEntered.countDown();
				boolean interrupted = false;
				while (true) {
					try {
						releaseRootWrite.await();
						break;
					} catch (InterruptedException e) {
						interrupted = true;
					}
				}
				if (interrupted) Thread.currentThread().interrupt();
			}
			return super.setRootData(data);
		}

		void release() {
			blockRootWrite = false;
			releaseRootWrite.countDown();
		}
	}

	/**
	 * #561: StackOverflowError from a maliciously deep value is recoverable once its
	 * stack unwinds, so the merge is rejected atomically without losing the dispatcher.
	 */
	@Test
	public void testMergeStackOverflowRejectedNotPropagated() {
		maxNodeServer = new NodeServer<>(new ThrowingLattice(), store, NodeConfig.port(-1));
		var c = maxNodeServer.getCursor();
		c.set(CVMLong.create(5));

		assertFalse(maxNodeServer.mergeIncoming(c, CVMLong.create(7)),
			"a recursive stack overflow must be caught and rejected");
		assertEquals(CVMLong.create(5), c.get(), "cursor unchanged after aborted merge");
	}

	/** Fatal VM errors must reach the dispatcher's fail-closed boundary. */
	@Test
	public void testFatalMergeErrorPropagates() {
		maxNodeServer = new NodeServer<>(new FatalThrowingLattice(), store, NodeConfig.port(-1));
		var c = maxNodeServer.getCursor();
		c.set(CVMLong.create(5));

		assertThrows(OutOfMemoryError.class,
			() -> maxNodeServer.mergeIncoming(c, CVMLong.create(7)));
		assertEquals(CVMLong.create(5), c.get(), "cursor unchanged after aborted fatal merge");
	}

	/**
	 * Test mergeValue with SetLattice
	 */
	@Test
	public void testMergeSetValue() {
		ALattice<ASet<ACell>> lattice = SetLattice.create();
		setNodeServer = new NodeServer<>(lattice, store);

		// Start with empty set
		assertTrue(setNodeServer.getLocalValue().isEmpty());

		// Merge with a set containing values using the public mergeValue method
		ASet<ACell> merged = setNodeServer.mergeValue(Sets.of(CVMLong.ONE, CVMLong.TWO));
		assertNotNull(merged);
		assertTrue(merged.contains(CVMLong.ONE));
		assertTrue(merged.contains(CVMLong.TWO));
		assertEquals(2, merged.count());

		ASet<ACell> result = setNodeServer.getLocalValue();
		assertTrue(result.contains(CVMLong.ONE));
		assertTrue(result.contains(CVMLong.TWO));
		assertEquals(2, result.count());

		// Merge with overlapping set
		merged = setNodeServer.mergeValue(Sets.of(CVMLong.TWO, CVMLong.create(3)));
		assertNotNull(merged);
		assertTrue(merged.contains(CVMLong.ONE));
		assertTrue(merged.contains(CVMLong.TWO));
		assertTrue(merged.contains(CVMLong.create(3)));
		assertEquals(3, merged.count());

		result = setNodeServer.getLocalValue();
		assertTrue(result.contains(CVMLong.ONE));
		assertTrue(result.contains(CVMLong.TWO));
		assertTrue(result.contains(CVMLong.create(3)));
		assertEquals(3, result.count());
	}

	/**
	 * Test peer management via the propagator
	 */
	@Test
	public void testPeerManagement() throws IOException, InterruptedException, java.util.concurrent.TimeoutException {
		ALattice<AInteger> lattice = MaxLattice.create();
		maxNodeServer = new NodeServer<>(lattice, store);
		propagator(maxNodeServer);
		maxNodeServer.launch();

		LatticePropagator propagator = propagator(maxNodeServer);
		assertNotNull(propagator);

		// Create Convex connections to the server (using loopback addresses for testing)
		InetSocketAddress serverAddress = maxNodeServer.getHostAddress();
		ConvexRemote peer1 = ConvexRemote.connect(serverAddress);
		ConvexRemote peer2 = ConvexRemote.connect(serverAddress);

		// Initially no peers
		Set<Convex> peers = propagator.getPeers();
		assertTrue(peers.isEmpty());

		// Add peers with identity keys
		AccountKey key1 = AKeyPair.generate().getAccountKey();
		AccountKey key2 = AKeyPair.generate().getAccountKey();
		propagator.addPeer(key1, peer1);
		propagator.addPeer(key2, peer2);

		peers = propagator.getPeers();
		assertEquals(2, peers.size());
		assertTrue(peers.contains(peer1));
		assertTrue(peers.contains(peer2));

		// Remove a peer by identity
		propagator.removePeer(key1);
		peers = propagator.getPeers();
		assertEquals(1, peers.size());
		assertTrue(peers.contains(peer2));
		assertFalse(peers.contains(peer1));

		// Clean up
		peer1.close();
		peer2.close();
	}

	/**
	 * Test pull(Convex) using Convex connection
	 */
	@Test
	public void testPullFromPeer() throws Exception {
		ALattice<AInteger> lattice = MaxLattice.create();
		maxNodeServer = new NodeServer<>(lattice, store);
		allowSingleGroupInbound(maxNodeServer);
		maxNodeServer.launch();

		// Create a Convex connection to the server
		InetSocketAddress serverAddress = maxNodeServer.getHostAddress();
		ConvexRemote peer = ConvexRemote.connect(serverAddress);
		
		try {
			// Pull from peer - should get the current value (zero initially)
			CompletableFuture<AInteger> future =
				maxNodeServer.pull(propagator(maxNodeServer),peer);
			AInteger result = future.get(5, TimeUnit.SECONDS);
			
			assertNotNull(result);
			assertEquals(CVMLong.ZERO, result); // Should return initial zero value
		} finally {
			peer.close();
		}
	}

	/**
	 * Test that server cannot be launched twice
	 */
	@Test
	public void testLaunchTwice() throws IOException, InterruptedException {
		ALattice<AInteger> lattice = MaxLattice.create();
		maxNodeServer = new NodeServer<>(lattice, store);

		// First launch should work (even though network server is stubbed)
		maxNodeServer.launch();
		assertTrue(maxNodeServer.isRunning());

		// Second launch should throw exception
		assertThrows(IllegalStateException.class, () -> {
			maxNodeServer.launch();
		});
	}

	/**
	 * Test close operation
	 */
	@Test
	public void testClose() throws IOException, InterruptedException {
		ALattice<AInteger> lattice = MaxLattice.create();
		maxNodeServer = new NodeServer<>(lattice, store);

		maxNodeServer.launch();
		assertTrue(maxNodeServer.isRunning());

		maxNodeServer.close();
		assertFalse(maxNodeServer.isRunning());

		// Closing again should be safe
		maxNodeServer.close();
		assertFalse(maxNodeServer.isRunning());
	}

	/**
	 * Test port configuration
	 */
	@Test
	public void testPortConfiguration() {
		ALattice<AInteger> lattice = MaxLattice.create();
		
		// Test with null port
		maxNodeServer = new NodeServer<>(lattice, store);
		assertEquals(null, maxNodeServer.getPort());

		// Test with specific port
		NodeServer<AInteger> server2 = new NodeServer<>(lattice, store, NodeConfig.port(19999));
		assertEquals(Integer.valueOf(19999), server2.getPort());
		
		try {
			server2.close();
		} catch (IOException e) {
			// Ignore
		}
	}

	/**
	 * Test that getLocalValue returns current cursor value
	 */
	@Test
	public void testGetLocalValue() {
		ALattice<AInteger> lattice = MaxLattice.create();
		maxNodeServer = new NodeServer<>(lattice, store);

		// Initial value
		AInteger value1 = maxNodeServer.getLocalValue();
		assertEquals(CVMLong.ZERO, value1);

		// Update cursor directly
		maxNodeServer.getCursor().set(CVMLong.create(42));

		// getLocalValue should reflect the change
		AInteger value2 = maxNodeServer.getLocalValue();
		assertEquals(CVMLong.create(42), value2);
	}

	/**
	 * Test connecting to NodeServer with ConvexRemote
	 */
	@Test
	public void testConvexRemoteConnection() throws IOException, InterruptedException, java.util.concurrent.TimeoutException {
		ALattice<AInteger> lattice = MaxLattice.create();
		maxNodeServer = new NodeServer<>(lattice, store);
		
		// Launch the server
		maxNodeServer.launch();
		assertTrue(maxNodeServer.isRunning());
		
		// Get the server address
		InetSocketAddress serverAddress = maxNodeServer.getHostAddress();
		assertNotNull(serverAddress, "Server should have a host address after launch");
		
		// Connect with ConvexRemote
		ConvexRemote convex = ConvexRemote.connect(serverAddress);
		assertNotNull(convex, "ConvexRemote connection should be created");
		assertTrue(convex.isConnected(), "ConvexRemote should be connected");
		
		// Clean up
		convex.close();
		assertFalse(convex.isConnected(), "ConvexRemote should be disconnected after close");
	}
	
	/**
	 * Test that a PING request returns a result
	 */
	@Test
	public void testPingRequest() throws IOException, InterruptedException, java.util.concurrent.TimeoutException, java.util.concurrent.ExecutionException {
		ALattice<AInteger> lattice = MaxLattice.create();
		maxNodeServer = new NodeServer<>(lattice, store);
		allowSingleGroupInbound(maxNodeServer);
		
		// Launch the server
		maxNodeServer.launch();
		assertTrue(maxNodeServer.isRunning());
		
		// Get the server address
		InetSocketAddress serverAddress = maxNodeServer.getHostAddress();
		assertNotNull(serverAddress, "Server should have a host address after launch");
		
		// Connect with ConvexRemote
		ConvexRemote convex = ConvexRemote.connect(serverAddress);
		assertNotNull(convex, "ConvexRemote connection should be created");
		
		try {
			// Create a PING message with ID 1
			// Payload format: [:PING id]
			CVMLong pingId = CVMLong.create(1);
			AVector<?> pingPayload = Vectors.create(MessageTag.PING, pingId);
			Message pingMessage = Message.create(MessageType.PING, pingPayload);
			
			// Send PING message and wait for result
			CompletableFuture<Result> resultFuture = convex.message(pingMessage);
			Result result = resultFuture.get(5, TimeUnit.SECONDS);
			
			// Verify result
			assertNotNull(result, "PING should return a result");
			assertEquals(pingId, result.getID(), "Result ID should match PING ID");
			assertFalse(result.isError(), "PING should succeed");
			assertNotNull(result.getValue(), "PING result should have a value");
		} finally {
			convex.close();
		}
	}
	
	/**
	 * Test that a LATTICE_QUERY request with an empty path returns a valid lattice value
	 */
	@Test
	public void testLatticeQueryEmptyPath() throws IOException, InterruptedException, java.util.concurrent.TimeoutException, java.util.concurrent.ExecutionException {
		ALattice<AInteger> lattice = MaxLattice.create();
		maxNodeServer = new NodeServer<>(lattice, store);
		allowSingleGroupInbound(maxNodeServer);
		
		// Launch the server
		maxNodeServer.launch();
		assertTrue(maxNodeServer.isRunning());

		// Set a value and sync so it flows through the propagator pipeline.
		// Synchronous commit: announce + setRootData run on this thread.
		maxNodeServer.getCursor().set(CVMLong.create(42));
		maxNodeServer.getCursor().sync();
		
		// Get the server address
		InetSocketAddress serverAddress = maxNodeServer.getHostAddress();
		assertNotNull(serverAddress, "Server should have a host address after launch");
		
		// Connect with ConvexRemote
		ConvexRemote convex = ConvexRemote.connect(serverAddress);
		assertNotNull(convex, "ConvexRemote connection should be created");
		
		try {
			// Create a LATTICE_QUERY message with empty path
			// Payload format: [:LQ id []]
			CVMLong queryId = CVMLong.create(2);
			AVector<ACell> emptyPath = Vectors.empty();
			AVector<?> queryPayload = Vectors.create(MessageTag.LATTICE_QUERY, queryId, emptyPath);
			Message queryMessage = Message.create(MessageType.LATTICE_QUERY, queryPayload);
			
			// Send LATTICE_QUERY message and wait for result
			CompletableFuture<Result> resultFuture = convex.message(queryMessage);
			Result result = resultFuture.get(5, TimeUnit.SECONDS);
			
			// Verify result
			assertNotNull(result, "LATTICE_QUERY should return a result");
			assertEquals(queryId, result.getID(), "Result ID should match query ID");
			assertFalse(result.isError(), "LATTICE_QUERY should succeed");
			
			// Verify the returned value is a valid lattice value (should be 42)
			ACell value = result.getValue();
			assertNotNull(value, "LATTICE_QUERY result should have a value");
			assertEquals(CVMLong.create(42), value, "LATTICE_QUERY should return the current lattice value");
		} finally {
			convex.close();
		}
	}

	/**
	 * Basic public-node configuration: the sole propagator shares the authoritative
	 * cursor store, and explicit operator policy grants an inbound Peer that one view.
	 * The Peer must be able to query the announced root and acquire its full value tree
	 * from the same store; otherwise larger query results cannot be consumed.
	 */
	@Test
	public void testPublicSinglePropagatorSharesNodeStore() throws Exception {
		Blob branch = Blobs.createRandom(400);
		ASet<ACell> expected = Sets.of(branch);
		setNodeServer = new NodeServer<>(SetLattice.create(), store);
		allowSingleGroupInbound(setNodeServer);
		setNodeServer.launch();

		LatticePropagator propagator = propagator(setNodeServer);
		assertNotNull(propagator);
		assertSame(store, propagator.getStore(),
			"the application group should share the authoritative cursor store");

		setNodeServer.getCursor().merge(expected);
		setNodeServer.getCursor().sync();
		Hash rootHash = expected.getHash();

		try (AStore peerStore = new MemoryStore();
				ConvexRemote peer = ConvexRemote.connect(setNodeServer.getHostAddress())) {
			peer.setStore(peerStore);
			AVector<?> query = Vectors.create(
				MessageTag.LATTICE_QUERY, CVMLong.create(70), Vectors.empty());
			Result result = peer.message(Message.create(MessageType.LATTICE_QUERY, query))
				.get(5, TimeUnit.SECONDS);

			assertFalse(result.isError());
			assertEquals(rootHash, result.getValue().getHash(),
				"the public view should be the sole propagator's announced root");
			ACell acquired = peer.acquire(rootHash, peerStore).get(5, TimeUnit.SECONDS);
			assertEquals(expected, acquired);
			assertNotNull(peerStore.refForHash(branch.getHash()),
				"missing branches should be served from the selected shared store");
		}
	}
	
	/**
	 * An inbound listener connection has no propagator identity, so it must not gain
	 * accidental access to the NodeServer's primary store.
	 */
	@Test
	public void testUnscopedDataRequestCannotReadPrimaryStore() throws Exception {
		Blob privateValue = Cells.persist(Blobs.createRandom(400), store);
		maxNodeServer = new NodeServer<>(MaxLattice.create(), store);
		maxNodeServer.launch();

		try (ConvexRemote convex = ConvexRemote.connect(maxNodeServer.getHostAddress())) {
			Message request = Message.createDataRequest(CVMLong.create(71), privateValue.getHash());
			Result denied=convex.message(request).get(5,TimeUnit.SECONDS);
			assertEquals(ErrorCodes.TRUST,denied.getErrorCode());
			assertEquals(CVMLong.create(71),denied.getID());
		}
	}

	/** Unverified public clients cannot pre-seed the propagator store with DATA. */
	@Test
	public void testUnverifiedDataDoesNotPersist() throws Exception {
		maxNodeServer = new NodeServer<>(MaxLattice.create(), store);
		allowSingleGroupInbound(maxNodeServer);
		maxNodeServer.launch();
		Blob unsolicited=Blobs.createRandom(400);

		try (ConvexRemote convex = ConvexRemote.connect(maxNodeServer.getHostAddress())) {
			assertTrue(convex.trySend(Message.createDataMessage(
				List.of(unsolicited),(int)CPoSConstants.MAX_MESSAGE_LENGTH)));

			// A following request is an ordered-dispatch barrier; no timing wait is needed.
			AVector<?> query=Vectors.create(MessageTag.LATTICE_QUERY,
				CVMLong.create(701),Vectors.empty());
			assertFalse(convex.message(Message.create(MessageType.LATTICE_QUERY,query))
				.get(5,TimeUnit.SECONDS).isError());
			assertNull(store.refForHash(unsolicited.getHash()));
		}
	}

	/** Interest-policy rejection is observable and occurs before persistence. */
	@Test
	public void testIngressRejectionDoesNotPersist() throws Exception {
		try (AStore ingressStore=new MemoryStore()) {
			Blob unsolicited=Blobs.createRandom(400);
			ASet<ACell> value=Sets.of(unsolicited);
			try (NodeServer<ASet<ACell>> node=new NodeServer<>(
					SetLattice.create(),ingressStore)) {
					LatticePropagator ingress=unattachedPropagator(node);
					ingress.setIngressFilter((path,received) -> null);
					node.addPropagator(ingress);
					node.setInboundPropagatorSelector(connection -> ingress);
				node.launch();
				try (ConvexRemote peer=ConvexRemote.connect(node.getHostAddress())) {
					AVector<?> payload=Vectors.create(MessageTag.LATTICE_VALUE,
						null,Vectors.empty(),value);
					Result result=peer.request(Message.create(
						MessageType.LATTICE_VALUE,payload)).get(5,TimeUnit.SECONDS);
					assertEquals(ErrorCodes.FORMAT,result.getErrorCode());
					assertEquals(1L,ingress.getInboundStats().mergesRejected());
					assertTrue(node.getLocalValue().isEmpty());
					assertNull(ingressStore.refForHash(unsolicited.getHash()));
				}
			}
		}
	}

	/** An assigned listener connection reads only its selected propagator store. */
	@Test
	public void testInboundDataRequestUsesSelectedPropagatorStore() throws Exception {
		try (AStore primaryStore = new MemoryStore();
				AStore publicStore = new MemoryStore()) {
			Blob privateValue = Cells.persist(Blobs.createRandom(400), primaryStore);
			Blob publicValue = Cells.persist(Blobs.createRandom(400), publicStore);
			LatticePropagator primary = new LatticePropagator(
				primaryStore,MaxLattice.create(),value -> value);
			LatticePropagator publicPropagator = new LatticePropagator(
				publicStore,MaxLattice.create(),value -> value);

			try (NodeServer<AInteger> node = new NodeServer<>(MaxLattice.create(), primaryStore)) {
				node.addPropagator(primary);
				node.addPropagator(publicPropagator);
				node.setInboundPropagatorSelector(connection -> publicPropagator);
				node.launch();

				try (ConvexRemote peer = ConvexRemote.connect(node.getHostAddress())) {
					Message request = Message.createDataRequest(CVMLong.create(74),
						publicValue.getHash(), privateValue.getHash());
					Result result = peer.message(request).get(5, TimeUnit.SECONDS);
					AVector<?> values = result.getValue();

					assertFalse(result.isError());
					assertEquals(publicValue, values.get(0));
					assertNull(values.get(1),
						"an assigned public connection must not search the primary store");
				}
			}
		}
	}

	/** An unassigned connection cannot select the primary lattice query view. */
	@Test
	public void testUnscopedLatticeQueryCannotReadPrimaryView() throws Exception {
		maxNodeServer = new NodeServer<>(MaxLattice.create(), store);
		maxNodeServer.launch();

		try (ConvexRemote convex = ConvexRemote.connect(maxNodeServer.getHostAddress())) {
			AVector<?> payload = Vectors.create(
				MessageTag.LATTICE_QUERY, CVMLong.create(73), Vectors.empty());
			Result denied=convex.message(Message.create(MessageType.LATTICE_QUERY,payload))
				.get(5,TimeUnit.SECONDS);
			assertEquals(ErrorCodes.TRUST,denied.getErrorCode());
			assertEquals(CVMLong.create(73),denied.getID());
		}
	}

	/**
	 * A configured propagator connection serves reverse requests from its own store
	 * and does not search another propagator's store. Futures provide deterministic
	 * synchronisation with both network directions and the virtual request worker.
	 */
	@Test
	public void testPropagatorDataRequestUsesOnlyItsStore() throws Exception {
		try (AStore allowedStore = new MemoryStore();
				AStore otherStore = new MemoryStore();
				AStore decodeStore = new MemoryStore();
				NettyServer requester = new NettyServer(0)) {
			Blob allowed = Cells.persist(Blobs.createRandom(400), allowedStore);
			Blob other = Cells.persist(Blobs.createRandom(400), otherStore);
			CompletableFuture<AConnection> reverseConnection = new CompletableFuture<>();
			CompletableFuture<Message> responseFuture = new CompletableFuture<>();

			requester.setReceiveAction(message -> {
				try {
					if (message.getResultID() != null) {
						responseFuture.complete(message);
					} else {
						reverseConnection.complete(message.getConnection());
					}
				} catch (Exception e) {
					responseFuture.completeExceptionally(e);
					reverseConnection.completeExceptionally(e);
				}
			});
			requester.launch();

			LatticeConnectionManager manager = new LatticeConnectionManager(allowedStore);
			ConvexRemote peer = ConvexRemote.connect(requester.getHostAddress());
			try {
				manager.addPeer(AKeyPair.generate().getAccountKey(), peer);
				assertTrue(peer.trySend(Message.createPing(1)));

				AConnection connection = reverseConnection.get(5, TimeUnit.SECONDS);
				Message request = Message.createDataRequest(CVMLong.create(72),
					allowed.getHash(), other.getHash());
				assertTrue(connection.sendMessage(request));

				Message response = responseFuture.get(5, TimeUnit.SECONDS);
				response.getPayload(decodeStore);
				Result result = response.toResult();
				AVector<?> values = result.getValue();
				assertEquals(CVMLong.create(72), result.getID());
				assertEquals(allowed, values.get(0),
					"data announced to this propagator should be serviceable");
				assertNull(values.get(1),
					"the manager must not search a different propagator store");
			} finally {
				manager.close();
				peer.close();
			}
		}
	}

	/**
	 * An unverified connection may submit a complete lattice value, but cannot
	 * trigger missing-cell acquisition into any node store.
	 */
	@Test
	public void testUnverifiedIncompleteValueDoesNotAcquire() throws Exception {
		try (AStore primaryStore = new MemoryStore();
				AStore ingressStore = new MemoryStore();
				BlockingReadMemoryStore sourceStore = new BlockingReadMemoryStore()) {
			Blob missingBranch = Blobs.createRandom(400);
			ASet<ACell> remoteValue = Sets.of(missingBranch);
			remoteValue = Cells.persist(remoteValue, sourceStore);
			sourceStore.blockedHash = missingBranch.getHash();

			LatticePropagator primary = new LatticePropagator(
				primaryStore,SetLattice.create(),value -> value);
			LatticePropagator ingress = new LatticePropagator(
				ingressStore,SetLattice.create(),value -> value);
			try (NodeServer<ASet<ACell>> receiver = new NodeServer<>(
					SetLattice.create(),primaryStore)) {
				receiver.addPropagator(primary);
				receiver.addPropagator(ingress);
				receiver.setInboundPropagatorSelector(connection -> ingress);
				receiver.launch();

				LatticeConnectionManager sourceManager = new LatticeConnectionManager(sourceStore);
				ConvexRemote source = ConvexRemote.connect(receiver.getHostAddress());
				try {
					sourceManager.addPeer(AKeyPair.generate().getAccountKey(), source);
					AVector<?> payload = Vectors.create(
						MessageTag.LATTICE_VALUE, null, Vectors.empty(), remoteValue);
					// Encode only the protocol root. The large Blob remains an indirect
					// reference which an unverified receiver must reject.
					Message partial = Message.create(
						MessageType.LATTICE_VALUE, payload, payload.getEncoding());
					assertTrue(source.trySend(partial));
					// A correlated ping on the same ordered connection is the processing
					// barrier; no timing poll or sleep is needed.
					Result barrier=source.message(Message.createPing(81))
						.get(5,TimeUnit.SECONDS);
					assertFalse(barrier.isError());
					assertEquals(1L,sourceStore.requested.getCount(),
						"unverified input must not start missing-data acquisition");
					assertTrue(receiver.getLocalValue().isEmpty(),
						"partial data must never reach the lattice cursor");
					assertNull(primaryStore.refForHash(missingBranch.getHash()),
						"acquisition must not deposit unvalidated data in the primary store");
					assertNull(ingressStore.refForHash(missingBranch.getHash()),
						"the withheld branch should not appear before its response arrives");
				} finally {
					sourceStore.release.countDown();
					sourceManager.close();
					source.close();
				}
			}
		}
	}

	/**
	 * NodeServer owns each inbound Acquiror until its worker has actually stopped.
	 * This models a store operation that cannot honour interruption immediately: the
	 * first close must report an incomplete shutdown, and a retry is safe once the
	 * operation reaches its controlled completion point.
	 */
	@Test
	public void testCloseWaitsForInboundAcquirorTermination() throws Exception {
		MemoryStore primaryStore = new MemoryStore();
		BlockingAcquisitionMemoryStore ingressStore = new BlockingAcquisitionMemoryStore();
		MemoryStore sourceStore = new MemoryStore();
		NodeServer<ASet<ACell>> receiver = null;
		LatticeConnectionManager sourceManager = null;
		ConvexRemote source = null;
		try {
			Blob missingBranch = Blobs.createRandom(400);
			ASet<ACell> remoteValue = Cells.persist(Sets.of(missingBranch), sourceStore);
			// Acquisition should target the precise missing leaf reported by decoding,
			// without dereferencing the incomplete lattice value a second time.
			ingressStore.blockedHash = missingBranch.getHash();

			NodeConfig config = NodeConfig.create(Maps.of(
				NodeConfig.PORT, CVMLong.ZERO,
				NodeConfig.INBOUND_SHUTDOWN_TIMEOUT, CVMLong.create(100)));
			LatticePropagator primary = new LatticePropagator(
				primaryStore,SetLattice.create(),value -> value,config);
			LatticePropagator ingress = new LatticePropagator(
				ingressStore,SetLattice.create(),value -> value,config);
			receiver = new NodeServer<>(SetLattice.create(), primaryStore, config);
			receiver.addPropagator(primary);
			receiver.addPropagator(ingress);
			AKeyPair sourceKey=AKeyPair.generate();
			receiver.setInboundPropagatorSelector(connection -> {
				connection.setTrustedKey(sourceKey.getAccountKey());
				return ingress;
			});
			receiver.launch();

			sourceManager = new LatticeConnectionManager(sourceStore);
			source = ConvexRemote.connect(receiver.getHostAddress());
			sourceManager.addPeer(sourceKey.getAccountKey(), source);

			AVector<?> payload = Vectors.create(
				MessageTag.LATTICE_VALUE, null, Vectors.empty(), remoteValue);
			Message partial = Message.create(
				MessageType.LATTICE_VALUE, payload, payload.getEncoding());
			assertTrue(source.trySend(partial));
			assertTrue(ingressStore.acquisitionReadEntered.await(5, TimeUnit.SECONDS),
				"the inbound Acquiror should enter the controlled store operation");

			receiver.close();
			assertEquals(NodeServer.LifecycleState.STOPPED,receiver.getLifecycleState(),
				"a failed policy-group drain must not strand the node lifecycle");
			assertTrue(receiver.getLocalValue().isEmpty(),
				"an acquisition cancelled during shutdown must never merge");
			assertNull(primaryStore.refForHash(missingBranch.getHash()),
				"cancelled acquisition must not expose data to the primary store");

			ingressStore.release();
			assertTrue(ingressStore.acquisitionReadFinished.await(5, TimeUnit.SECONDS));
			receiver.close();
		} finally {
			ingressStore.release();
			if (receiver != null) receiver.close();
			if (sourceManager != null) sourceManager.close();
			if (source != null) source.close();
			sourceStore.close();
			ingressStore.close();
			primaryStore.close();
		}
	}

	// ===== #567: public URL validation =====

	/**
	 * The URL validator accepts public hosts (hostnames it does not resolve, and public IP
	 * literals) and rejects loopback / private / link-local / malformed URLs.
	 */
	@Test
	public void testPublicURLValidation() {
		// Acceptable: public hostname (never resolved) and a public IP literal
		assertNull(NodeConfig.validatePublicURL("tcp://peer.example.com:18888", false));
		assertNull(NodeConfig.validatePublicURL("tcp://93.184.216.34:18888", false));
		assertNull(NodeConfig.validatePublicURL("tcp://[2001:db8::1]:18888", false));
		// A public hostname that happens to boundary the 172.16/12 range on either side
		assertNull(NodeConfig.validatePublicURL("tcp://172.15.0.1:18888", false));
		assertNull(NodeConfig.validatePublicURL("tcp://172.32.0.1:18888", false));

		// Rejected: localhost and loopback
		assertNotNull(NodeConfig.validatePublicURL("tcp://localhost:18888", false));
		assertNotNull(NodeConfig.validatePublicURL("tcp://127.0.0.1:18888", false));
		assertNotNull(NodeConfig.validatePublicURL("tcp://[::1]:18888", false));
		// Rejected: RFC1918 private ranges
		assertNotNull(NodeConfig.validatePublicURL("tcp://10.1.2.3:18888", false));
		assertNotNull(NodeConfig.validatePublicURL("tcp://172.20.0.1:18888", false));
		assertNotNull(NodeConfig.validatePublicURL("tcp://192.168.1.1:18888", false));
		// Rejected: link-local, wildcard, IPv6 ULA
		assertNotNull(NodeConfig.validatePublicURL("tcp://169.254.1.1:18888", false));
		assertNotNull(NodeConfig.validatePublicURL("tcp://0.0.0.0:18888", false));
		assertNotNull(NodeConfig.validatePublicURL("tcp://[fc00::1]:18888", false));
		// Rejected: missing scheme / host / port, malformed
		assertNotNull(NodeConfig.validatePublicURL("peer.example.com", false));
		assertNotNull(NodeConfig.validatePublicURL("tcp://peer.example.com", false));
		assertNotNull(NodeConfig.validatePublicURL("ht tp://bad host", false));
		assertNotNull(NodeConfig.validatePublicURL("", false));
		assertNotNull(NodeConfig.validatePublicURL(null, false));

		// allowPrivate override: private addresses become acceptable, malformed still rejected
		assertNull(NodeConfig.validatePublicURL("tcp://localhost:18888", true));
		assertNull(NodeConfig.validatePublicURL("tcp://192.168.1.1:18888", true));
		assertNotNull(NodeConfig.validatePublicURL("tcp://peer.example.com", true)); // still missing port
	}

	/**
	 * #568: the merge context is configuration-only — setMergeContext is allowed before
	 * launch() but rejected once the node is running.
	 */
	@Test
	public void testSetMergeContextConfigurationOnly() throws IOException, InterruptedException {
		AKeyPair kp = AKeyPair.generate();
		maxNodeServer = new NodeServer<>(MaxLattice.create(), store, NodeConfig.port(-1));

		// Before launch: permitted
		maxNodeServer.setMergeContext(LatticeContext.create(null, kp));
		maxNodeServer.launch();

		// After launch: rejected
		assertThrows(IllegalStateException.class,
			() -> maxNodeServer.setMergeContext(LatticeContext.EMPTY));
	}

	@Test
	public void testRootComponentSyncPersistsBeforeLaunch() throws Exception {
		maxNodeServer=new NodeServer<>(MaxLattice.create(),store,NodeConfig.port(-1));
		maxNodeServer.getCursor().set(CVMLong.create(42));

		maxNodeServer.getRootComponent().sync();

		assertEquals(CVMLong.create(42),store.getRootData());
	}

	@Test
	public void testPropagationGroupMayUseIndependentServingStore() {
		maxNodeServer=new NodeServer<>(MaxLattice.create(),store,NodeConfig.port(-1));
		try (AStore otherStore=new MemoryStore()) {
			LatticePropagator publicView=new LatticePropagator(
				otherStore,MaxLattice.create(),value -> value);
			maxNodeServer.addPropagator(publicView);
			assertSame(otherStore,publicView.getStore());
		}
	}

	/**
	 * Lifecycle state is explicit, and configuration freezes atomically when the
	 * first launch begins. Latches hold launch inside its initial root checkpoint;
	 * the competing mutation future must remain blocked on the lifecycle monitor
	 * until launch publishes RUNNING, then reject against that completed state.
	 */
	@Test
	public void testLifecycleTransitionsFreezeTopologyDeterministically() throws Exception {
		BlockingRootStore testStore = new BlockingRootStore();
		NodeServer<AInteger> node = new NodeServer<>(MaxLattice.create(), testStore, NodeConfig.port(-1));
		LatticePropagator primary = new LatticePropagator(
			testStore,MaxLattice.create(),value -> value,NodeConfig.port(-1));
		node.addPropagator(primary);
		node.setMergeContext(LatticeContext.EMPTY);

		assertEquals(NodeServer.LifecycleState.NEW, node.getLifecycleState());
		assertThrows(UnsupportedOperationException.class, node.getPropagators()::clear,
			"callers must not be able to reorder or remove attached propagators");

		CompletableFuture<Void> launchFuture = CompletableFuture.runAsync(() -> {
			try {
				node.launch();
			} catch (IOException | InterruptedException e) {
				throw new CompletionException(e);
			}
		});

		CountDownLatch mutationStarted = new CountDownLatch(1);
		try {
			assertTrue(testStore.rootWriteEntered.await(5, TimeUnit.SECONDS));
			assertEquals(NodeServer.LifecycleState.STARTING, node.getLifecycleState());

			CompletableFuture<Throwable> mutationFuture = CompletableFuture.supplyAsync(() -> {
				mutationStarted.countDown();
				try {
					node.setMergeContext(LatticeContext.EMPTY);
					return null;
				} catch (Throwable t) {
					return t;
				}
			});
			assertTrue(mutationStarted.await(5, TimeUnit.SECONDS));
			assertFalse(mutationFuture.isDone(),
				"configuration mutation must serialize behind the in-progress launch");

			testStore.release();
			launchFuture.get(5, TimeUnit.SECONDS);
			assertEquals(NodeServer.LifecycleState.RUNNING, node.getLifecycleState());
			assertTrue(mutationFuture.get(5, TimeUnit.SECONDS) instanceof IllegalStateException);
			assertThrows(IllegalStateException.class, () -> node.addPropagator(primary));

			node.close();
			assertEquals(NodeServer.LifecycleState.STOPPED, node.getLifecycleState());
			assertThrows(IllegalStateException.class,
				() -> node.setMergeContext(LatticeContext.EMPTY),
				"identity and topology remain frozen across relaunches");

			node.launch();
			assertEquals(NodeServer.LifecycleState.RUNNING, node.getLifecycleState(),
				"a stopped node may relaunch with its original immutable topology");
		} finally {
			testStore.release();
			try {
				launchFuture.get(5, TimeUnit.SECONDS);
			} catch (Exception e) {
				// The assertions above report the original launch failure if there was one.
			}
			node.close();
			testStore.close();
		}
	}

	// ===== #566: per-connection metrics & circuit-breaker =====

	/** Minimal AConnection test double: records close(), never actually sends. */
	static final class RecordingConnection extends AConnection {
		volatile boolean closed = false;
		final AtomicInteger sent = new AtomicInteger();
		@Override public boolean sendMessage(Message msg) { return true; }
		@Override public boolean trySendMessage(Message msg) { sent.incrementAndGet(); return true; }
		@Override public InetSocketAddress getRemoteAddress() { return new InetSocketAddress("192.0.2.1", 30000); }
		@Override public boolean isClosed() { return closed; }
		@Override public void close() { closed = true; }
		@Override public long getReceivedCount() { return 0; }
	}

	/** Store gate used to make the DATA_REQUEST/acquire/merge transition deterministic. */
	static final class BlockingReadMemoryStore extends MemoryStore {
		final CountDownLatch requested = new CountDownLatch(1);
		final CountDownLatch release = new CountDownLatch(1);
		volatile Hash blockedHash;

		@Override
		public <T extends ACell> Ref<T> refForHash(Hash hash) {
			if (blockedHash != null && blockedHash.equals(hash)) {
				requested.countDown();
				boolean interrupted = false;
				while (true) {
					try {
						release.await();
						break;
					} catch (InterruptedException e) {
						interrupted = true;
					}
				}
				if (interrupted) Thread.currentThread().interrupt();
			}
			return super.refForHash(hash);
		}
	}

	/** Store gate for proving shutdown waits for the Acquiror's store access. */
	static final class BlockingAcquisitionMemoryStore extends MemoryStore {
		final CountDownLatch acquisitionReadEntered = new CountDownLatch(1);
		final CountDownLatch acquisitionReadFinished = new CountDownLatch(1);
		final CountDownLatch release = new CountDownLatch(1);
		volatile Hash blockedHash;

		@Override
		public <T extends ACell> Ref<T> refForHash(Hash hash) {
			if (blockedHash != null && blockedHash.equals(hash)
					&& "Acquiror".equals(Thread.currentThread().getName())) {
				acquisitionReadEntered.countDown();
				boolean interrupted = false;
				while (true) {
					try {
						release.await();
						break;
					} catch (InterruptedException e) {
						// Model a store call that can only stop at its own safe boundary.
						interrupted = true;
					}
				}
				if (interrupted) Thread.currentThread().interrupt();
				acquisitionReadFinished.countDown();
			}
			return super.refForHash(hash);
		}

		void release() {
			release.countDown();
		}
	}

	/** A failed publication checkpoint must roll back every service started by launch(). */
	@Test
	public void testPublicationFailureAbortsLaunch() throws Exception {
		FailingMemoryStore testStore = new FailingMemoryStore();
		testStore.failOnRootWrite = 1; // generic initial root publication fails
		NodeConfig cfg = NodeConfig.create(Maps.of(
			NodeConfig.PORT, CVMLong.ZERO));
		NodeServer<Index<Keyword, ACell>> node = new NodeServer<>(Lattice.ROOT, testStore, cfg);

		try {
			assertThrows(StoreException.class, node::launch);
			assertFalse(node.isRunning(), "a failed launch must not report a live node");
			assertEquals(NodeServer.LifecycleState.STOPPED, node.getLifecycleState(),
				"complete launch rollback must publish a retryable stopped state");
			InetSocketAddress closedAddress = new InetSocketAddress("127.0.0.1", node.getPort());
			try (java.net.Socket socket = new java.net.Socket()) {
				assertThrows(IOException.class,
					() -> socket.connect(closedAddress, 1000),
					"the listener opened earlier in launch must be closed before the failure returns");
			}

			testStore.failOnRootWrite = -1;
			node.launch();
			assertTrue(node.isRunning(), "the same NodeServer should be launchable after cleanup");
		} finally {
			testStore.failOnRootWrite = -1;
			node.close();
			testStore.close();
		}
	}

	/** A dominated pull must re-publish the merged root, never the raw peer value. */
	@Test
	public void testPullCannotDemoteAuthoritativeRoot() throws Exception {
		maxNodeServer = new NodeServer<>(MaxLattice.create(), store);
		try (AStore remoteStore = new MemoryStore();
				NodeServer<AInteger> remote = new NodeServer<>(MaxLattice.create(), remoteStore)) {
			allowSingleGroupInbound(remote);
			propagator(maxNodeServer);
			maxNodeServer.launch();
			remote.launch();

			remote.getCursor().set(CVMLong.create(5));
			syncAndAwaitPropagator(remote);
			maxNodeServer.getCursor().set(CVMLong.create(10));
			syncAndAwaitPropagator(maxNodeServer);

			try (ConvexRemote peer = ConvexRemote.connect(remote.getHostAddress())) {
				assertEquals(CVMLong.create(10),
					maxNodeServer.pull(propagator(maxNodeServer),peer).get(5, TimeUnit.SECONDS));
			}

			assertEquals(CVMLong.create(10), maxNodeServer.getLocalValue());
			assertEquals(CVMLong.create(10), propagator(maxNodeServer).getLastAnnouncedValue(),
				"the queryable root must remain the post-merge NodeServer root");
			assertEquals(CVMLong.create(10), store.getRootData(),
				"the persisted root must not be demoted to the raw pulled value");
		}
	}

	/** Message test double that simulates pathological recursion during payload decoding. */
	static final class StackOverflowMessage extends Message {
		StackOverflowMessage(AConnection connection) {
			super(MessageType.PING, null, null, connection);
		}

		@Override
		public <T extends ACell> T getPayload(AStore store) {
			throw new StackOverflowError("simulated recursive decoder failure");
		}
	}

	/**
	 * Message that models an interrupt-resistant merge or store call. Shutdown may
	 * interrupt the dispatcher, but real third-party or filesystem code is not obliged
	 * to return immediately, so the test controls when payload decoding may finish.
	 */
	static final class BlockingMessage extends Message {
		final CountDownLatch entered = new CountDownLatch(1);
		final CountDownLatch release = new CountDownLatch(1);
		final CountDownLatch decoded = new CountDownLatch(1);

		BlockingMessage() {
			super(MessageType.PING, Vectors.of(MessageTag.PING, CVMLong.ONE), null, null);
		}

		@Override
		public <T extends ACell> T getPayload(AStore store) {
			entered.countDown();
			boolean interrupted = false;
			while (true) {
				try {
					release.await();
					break;
				} catch (InterruptedException e) {
					// Deliberately model code that cannot honour interruption until its
					// underlying operation reaches a safe completion point.
					interrupted = true;
				}
			}
			if (interrupted) Thread.currentThread().interrupt();
			try {
				return super.getPayload(store);
			} catch (convex.core.exceptions.BadFormatException e) {
				throw new AssertionError(e);
			} finally {
				decoded.countDown();
			}
		}
	}

	private static Message latticeValue(ACell value, AConnection conn) {
		AVector<?> payload = Vectors.create(MessageTag.LATTICE_VALUE, null, Vectors.empty(), value);
		return Message.create(MessageType.LATTICE_VALUE, payload).withConnection(conn);
	}

	/** A recoverable Error in one message must not terminate the single inbound consumer. */
	@Test
	public void testInboundDispatcherSurvivesStackOverflow() throws Exception {
		RecordingConnection faulting = new RecordingConnection();
		try (NodeServer<AInteger> node = new NodeServer<>(MaxLattice.create(), store)) {
			LatticePropagator propagator=propagator(node);
			node.launch();
			CompletableFuture<ACell> nextAnnounce = propagator.nextAnnounce();

			assertNull(propagator.deliverIncomingMessage(new StackOverflowMessage(faulting)));
			assertNull(propagator.deliverIncomingMessage(latticeValue(CVMLong.create(42), null)));

			assertEquals(CVMLong.create(42), nextAnnounce.get(10, TimeUnit.SECONDS));
			assertTrue(faulting.closed, "the connection responsible for the Error must be closed");
			assertTrue(node.isRunning(), "a recoverable message Error must not stop the node");
		}
	}

	/** A failed propagator drain must not prevent authoritative node shutdown. */
	@Test
	public void testCloseIsolatesInboundDrainTimeout() throws Exception {
		NodeConfig cfg = NodeConfig.create(Maps.of(
			NodeConfig.PORT, CVMLong.ZERO,
			NodeConfig.INBOUND_SHUTDOWN_TIMEOUT, CVMLong.create(100)));
		NodeServer<AInteger> node = new NodeServer<>(MaxLattice.create(), store, cfg);
		BlockingMessage blocking = new BlockingMessage();

		try {
			LatticePropagator propagator=propagator(node);
			node.launch();
			assertNull(propagator.deliverIncomingMessage(blocking));
			assertTrue(blocking.entered.await(5, TimeUnit.SECONDS));

			node.close();
			assertFalse(node.isRunning());
			assertEquals(NodeServer.LifecycleState.STOPPED,node.getLifecycleState());
			assertThrows(IllegalStateException.class,
				() -> node.setMergeContext(LatticeContext.EMPTY),
				"configuration remains frozen after the first launch");
			assertThrows(IllegalStateException.class,
				() -> node.addPropagator(propagator));

			blocking.release.countDown();
			assertTrue(blocking.decoded.await(5, TimeUnit.SECONDS));
		} finally {
			blocking.release.countDown();
			node.close();
		}
	}

	/**
	 * #566: per-connection counters track accepts/rejects, and the circuit-breaker closes a
	 * connection after the configured number of consecutive bad messages (an accepted merge
	 * resets the streak).
	 */
	@Test
	public void testCircuitBreakerAndInboundStats() {
		NodeConfig cfg = NodeConfig.create(Maps.of(
			NodeConfig.PORT, CVMLong.create(-1),
			NodeConfig.MAX_CONSECUTIVE_REJECTS, CVMLong.create(3)
		));
		maxNodeServer = new NodeServer<>(MaxLattice.create(), store, cfg);
		maxNodeServer.addPropagator(new LatticePropagator(
			store,MaxLattice.create(),value -> value,cfg));
		allowSingleGroupInbound(maxNodeServer);
		LatticePropagator endpoint=propagator(maxNodeServer);

		// Wrong type for MaxLattice<AInteger> — mergeIncoming rejects it
		ACell badValue = convex.core.data.Blob.wrap(new byte[8]);

		// A connection that sends two bad then one good message stays open; the accept resets the streak
		RecordingConnection tracked = new RecordingConnection();
		endpoint.handleIncomingMessage(latticeValue(badValue, tracked));            // reject 1
		endpoint.handleIncomingMessage(latticeValue(badValue, tracked));            // reject 2
		endpoint.handleIncomingMessage(latticeValue(CVMLong.create(42), tracked));  // accept

		LatticePropagator.InboundStats acceptedStats=endpoint.getInboundStats();
		assertEquals(3,acceptedStats.messagesReceived());
		assertEquals(2,acceptedStats.mergesRejected());
		assertEquals(1,acceptedStats.mergesAccepted());
		assertFalse(tracked.isClosed(), "Accept reset the streak, so the breaker must not trip");

		// A connection sending only bad messages trips the breaker at the limit
		RecordingConnection abusive = new RecordingConnection();
		endpoint.handleIncomingMessage(latticeValue(badValue, abusive)); // 1
		endpoint.handleIncomingMessage(latticeValue(badValue, abusive)); // 2
		assertFalse(abusive.isClosed(), "Below the threshold the connection stays open");
		endpoint.handleIncomingMessage(latticeValue(badValue, abusive)); // 3 == limit -> trip
		assertTrue(abusive.isClosed(), "Connection closed after reaching the consecutive-reject limit");

		// Aggregate stats reflect the surviving tracked connection (the abusive one was pruned on trip)
		LatticePropagator.InboundStats aggregate=endpoint.getInboundStats();
		assertTrue(aggregate.mergesAccepted()>=1);
		assertTrue(aggregate.mergesRejected()>=2);
	}

	/**
	 * #566: closed connections drain from the stats map via the periodic sweep (so an idle
	 * node cleans up without inbound traffic), and removeConnection is the explicit single-sink
	 * teardown.
	 */
	@Test
	public void testConnectionStatsSweep() {
		maxNodeServer = new NodeServer<>(MaxLattice.create(), store, NodeConfig.port(-1));
		LatticePropagator endpoint=propagator(maxNodeServer);

		RecordingConnection open = new RecordingConnection();
		RecordingConnection gone = new RecordingConnection();
		endpoint.handleIncomingMessage(latticeValue(CVMLong.create(1), open));
		endpoint.handleIncomingMessage(latticeValue(CVMLong.create(1), gone));
		assertEquals(2,endpoint.getInboundStats().connections());

		// A closed connection is pruned by the sweep; the open one survives
		gone.close();
		endpoint.sweepClosedInboundConnections();
		assertEquals(1,endpoint.getInboundStats().connections());

		// removeConnection is the explicit single-sink teardown
		endpoint.removeInboundConnection(open);
		assertEquals(0,endpoint.getInboundStats().connections());
	}

	// ===== Gossip relay tests =====
	//
	// These tests verify that incoming lattice values reach the propagator.
	// IMPORTANT: Neither test calls sync() directly. The incoming message path
	// (processLatticeValue) calls sync() internally after merging — that is
	// what we are testing. The control test uses cursor.set + sync to prove
	// the propagator infrastructure itself works.

	/**
	 * Regression test: incoming LATTICE_VALUE messages must reach the propagator.
	 *
	 * <p>When a peer sends a LATTICE_VALUE, processLatticeValue merges it into
	 * the cursor and calls sync() to notify propagators. Without that internal
	 * sync(), the merged value dead-ends in the cursor — never persisted, never
	 * relayed to other peers. Gossip would be one-hop only.
	 *
	 * <p>This test sends a LATTICE_VALUE via the network (the actual incoming
	 * message path) and verifies the propagator receives it. No explicit sync()
	 * call is made in the test — we rely on processLatticeValue doing it.
	 */
	@Test
	public void testIncomingMergeRelayedToPropagator() throws Exception {
		ALattice<AInteger> lattice = MaxLattice.create();
		AStore testStore = new MemoryStore();

		NodeServer<AInteger> node = new NodeServer<>(lattice, testStore);
		allowSingleGroupInbound(node);

		try {
			node.launch();

			LatticePropagator propagator = propagator(node);
			assertNotNull(propagator, "Node should have a propagator after launch");

			// Launch seeds the store-backed announced view.
			assertEquals(CVMLong.ZERO, propagator.getLastAnnouncedValue(),
				"Propagator should publish the initial lattice value during launch");

			// Send a LATTICE_VALUE message via the network.
			// This exercises the full incoming path:
			//   network → processLatticeValue → mergeIncoming → sync (internal)
			// We do NOT call sync() here — processLatticeValue must do it.
			ConvexRemote convex = ConvexRemote.connect(node.getHostAddress());
			try {
				// Capture the announce future BEFORE sending, so the announce
				// triggered by the incoming message cannot be missed
				CompletableFuture<ACell> announced = propagator.nextAnnounce();

				AVector<ACell> emptyPath = Vectors.empty();
				CVMLong mergeID = CVMLong.create(81);
				AVector<?> payload = Vectors.create(
					MessageTag.LATTICE_VALUE, mergeID, emptyPath, CVMLong.create(42));
				Message msg = Message.create(MessageType.LATTICE_VALUE, payload);
				Result result = convex.message(msg).get(10, TimeUnit.SECONDS);
				assertEquals(mergeID, result.getID());
				assertFalse(result.isError());
				assertNull(result.getValue());
				// The acknowledgement confirms the authoritative merge and node-store
				// publication. Propagation policy fan-out completes independently.
				assertEquals(CVMLong.create(42),announced.get(5,TimeUnit.SECONDS));
			} finally {
				convex.close();
			}

			// Cursor should have the merged value
			assertEquals(CVMLong.create(42), node.getLocalValue(),
				"Cursor should reflect the incoming LATTICE_VALUE");

			// The propagator must have been notified (by processLatticeValue's sync)
			assertNotNull(propagator.getLastAnnouncedValue(),
				"Propagator should be notified after incoming LATTICE_VALUE — " +
				"if null, incoming merges are not relayed (gossip is broken)");
		} finally {
			node.close();
			testStore.close();
		}
	}

	/** Netty event loops must only enqueue; lattice merge and persistence run elsewhere. */
	@Test
	public void testIncomingMergeRunsOffNettyEventLoop() throws Exception {
		RecordingLattice lattice = new RecordingLattice();
		try (AStore testStore = new MemoryStore();
				NodeServer<AInteger> node = new NodeServer<>(lattice, testStore)) {
			allowSingleGroupInbound(node);
			node.launch();
			CompletableFuture<ACell> announced = propagator(node).nextAnnounce();
			try (ConvexRemote convex = ConvexRemote.connect(node.getHostAddress())) {
				AVector<?> payload = Vectors.create(
					MessageTag.LATTICE_VALUE, null, Vectors.empty(), CVMLong.create(42));
				convex.message(Message.create(MessageType.LATTICE_VALUE, payload));
				announced.get(10, TimeUnit.SECONDS);
			}
			assertTrue(lattice.mergeThreads.contains("Lattice propagator ingress"),
				"authoritative merge should run on the selected group's endpoint");
			assertTrue(lattice.mergeThreads.stream()
				.noneMatch(name -> name.startsWith("convex-netty")));
		}
	}

	@Test
	public void testPullPathDoesNotPullSibling() throws Exception {
		Keyword regionA=Keyword.create("region-a");
		Keyword regionB=Keyword.create("region-b");
		KeyedLattice lattice=KeyedLattice.create(
				regionA,MaxLattice.create(),regionB,MaxLattice.create());

		try (NodeServer<Index<Keyword,ACell>> source=new NodeServer<>(lattice,new MemoryStore());
				NodeServer<Index<Keyword,ACell>> target=new NodeServer<>(lattice,new MemoryStore())) {
			allowSingleGroupInbound(source);
			propagator(target);
			source.launch();
			target.launch();
			source.getCursor().path(regionA).merge(CVMLong.create(111));
			source.getCursor().path(regionB).merge(CVMLong.create(222));
			syncAndAwaitPropagator(source);

			try (ConvexRemote peer=ConvexRemote.connect(source.getHostAddress())) {
				assertEquals(CVMLong.create(111),
						target.pullPath(peer,regionA).get(5,TimeUnit.SECONDS));
				assertNull(target.getCursor().get(regionB));
			}
		}
	}

	@Test
	public void testConcurrentPullPathsUseIndependentResults() throws Exception {
		Keyword regionA=Keyword.create("region-a");
		Keyword regionB=Keyword.create("region-b");
		KeyedLattice lattice=KeyedLattice.create(
				regionA,MaxLattice.create(),regionB,MaxLattice.create());

		try (NodeServer<Index<Keyword,ACell>> source=new NodeServer<>(lattice,new MemoryStore());
				NodeServer<Index<Keyword,ACell>> target=new NodeServer<>(lattice,new MemoryStore())) {
			allowSingleGroupInbound(source);
			propagator(target);
			source.launch();
			target.launch();
			source.getCursor().path(regionA).merge(CVMLong.create(111));
			source.getCursor().path(regionB).merge(CVMLong.create(222));
			syncAndAwaitPropagator(source);

			try (ConvexRemote peer=ConvexRemote.connect(source.getHostAddress())) {
				CompletableFuture<ACell> a=target.pullPath(peer,regionA);
				CompletableFuture<ACell> b=target.pullPath(peer,regionB);
				assertEquals(CVMLong.create(111),a.get(5,TimeUnit.SECONDS));
				assertEquals(CVMLong.create(222),b.get(5,TimeUnit.SECONDS));
			}
		}
	}

	@Test
	public void testPullPathAcquiresIndirectValue() throws Exception {
		Keyword region=Keyword.create("region");
		KeyedLattice lattice=KeyedLattice.create(region,SetLattice.create());
		Blob branch=Blobs.createRandom(400);
		ASet<ACell> expected=Sets.of(branch);

		try (NodeServer<Index<Keyword,ACell>> source=new NodeServer<>(lattice,new MemoryStore());
				NodeServer<Index<Keyword,ACell>> target=new NodeServer<>(lattice,new MemoryStore())) {
			allowSingleGroupInbound(source);
			propagator(target);
			source.launch();
			target.launch();
			source.getCursor().path(region).merge(expected);
			syncAndAwaitPropagator(source);

			try (ConvexRemote peer=ConvexRemote.connect(source.getHostAddress())) {
				assertEquals(expected,target.pullPath(peer,region).get(5,TimeUnit.SECONDS));
				assertNotNull(target.getStore().refForHash(branch.getHash()));
			}
		}
	}

	@Test
	public void testPullPathAbsentAndRejectedValues() throws Exception {
		Keyword absent=Keyword.create("absent");
		Keyword rejected=Keyword.create("rejected");
		KeyedLattice sourceLattice=KeyedLattice.create(
				absent,MaxLattice.create(),rejected,SetLattice.create());
		KeyedLattice targetLattice=KeyedLattice.create(
				absent,MaxLattice.create(),rejected,MaxLattice.create());

		try (NodeServer<Index<Keyword,ACell>> source=new NodeServer<>(sourceLattice,new MemoryStore());
				NodeServer<Index<Keyword,ACell>> target=new NodeServer<>(targetLattice,new MemoryStore())) {
			allowSingleGroupInbound(source);
			propagator(target);
			source.launch();
			target.launch();
			target.getCursor().path(rejected).merge(CVMLong.create(7));
			target.getCursor().sync();
			source.getCursor().path(rejected).merge(Sets.of(CVMLong.ONE));
			syncAndAwaitPropagator(source);

			try (ConvexRemote peer=ConvexRemote.connect(source.getHostAddress())) {
				assertNull(target.pullPath(peer,absent).get(5,TimeUnit.SECONDS));
				assertEquals(CVMLong.create(7),
						target.pullPath(peer,rejected).get(5,TimeUnit.SECONDS));
			}
		}
	}

	@Test
	public void testPullNestedPath() throws Exception {
		Keyword outer=Keyword.create("outer");
		Keyword inner=Keyword.create("inner");
		KeyedLattice lattice=KeyedLattice.create(
				outer,KeyedLattice.create(inner,MaxLattice.create()));

		try (NodeServer<Index<Keyword,ACell>> source=new NodeServer<>(lattice,new MemoryStore());
				NodeServer<Index<Keyword,ACell>> target=new NodeServer<>(lattice,new MemoryStore())) {
			allowSingleGroupInbound(source);
			propagator(target);
			source.launch();
			target.launch();
			source.getCursor().path(outer,inner).merge(CVMLong.create(42));
			syncAndAwaitPropagator(source);

			try (ConvexRemote peer=ConvexRemote.connect(source.getHostAddress())) {
				assertEquals(CVMLong.create(42),
						target.pullPath(peer,outer,inner).get(5,TimeUnit.SECONDS));
			}
		}
	}

	@Test
	public void testLatticeQueryRejectsNonVectorPath() throws Exception {
		maxNodeServer=new NodeServer<>(MaxLattice.create(),store);
		allowSingleGroupInbound(maxNodeServer);
		maxNodeServer.launch();

		try (ConvexRemote peer=ConvexRemote.connect(maxNodeServer.getHostAddress())) {
			AVector<?> payload=Vectors.create(
					MessageTag.LATTICE_QUERY,null,Keyword.create("not-a-vector"));
			Result result=peer.request(Message.create(MessageType.LATTICE_QUERY,payload))
					.get(5,TimeUnit.SECONDS);
			assertEquals(ErrorCodes.ARGUMENT,result.getErrorCode());
		}
	}

	@Test
	public void testLatticeQueryRequiresExplicitPathVector() throws Exception {
		maxNodeServer=new NodeServer<>(MaxLattice.create(),store);
		allowSingleGroupInbound(maxNodeServer);
		maxNodeServer.launch();

		try (ConvexRemote peer=ConvexRemote.connect(maxNodeServer.getHostAddress())) {
			AVector<?> payload=Vectors.create(
				MessageTag.LATTICE_QUERY,CVMLong.create(84));
			Result result=peer.message(Message.create(MessageType.LATTICE_QUERY,payload))
				.get(5,TimeUnit.SECONDS);
			assertEquals(ErrorCodes.ARGUMENT,result.getErrorCode());
		}
	}

	@Test
	public void testLatticeValueRejectsNonVectorPath() throws Exception {
		maxNodeServer=new NodeServer<>(MaxLattice.create(),store);
		allowSingleGroupInbound(maxNodeServer);
		maxNodeServer.launch();

		try (ConvexRemote peer=ConvexRemote.connect(maxNodeServer.getHostAddress())) {
			AVector<?> payload=Vectors.create(MessageTag.LATTICE_VALUE,
				CVMLong.create(85),Keyword.create("not-a-vector"),CVMLong.create(42));
			Result result=peer.message(Message.create(MessageType.LATTICE_VALUE,payload))
				.get(5,TimeUnit.SECONDS);
			assertTrue(result.isError());
			assertEquals(ErrorCodes.FORMAT,result.getErrorCode());
			assertEquals(CVMLong.ZERO,maxNodeServer.getLocalValue());
		}
	}

	@Test
	public void testOptimisticLatticeValuePush() throws Exception {
		maxNodeServer=new NodeServer<>(MaxLattice.create(),store);
		allowSingleGroupInbound(maxNodeServer);
		maxNodeServer.launch();

		CompletableFuture<ACell> announced=propagator(maxNodeServer).nextAnnounce();
		try (ConvexRemote peer=ConvexRemote.connect(maxNodeServer.getHostAddress())) {
			AVector<?> payload=Vectors.create(
				MessageTag.LATTICE_VALUE,Vectors.empty(),CVMLong.create(42));
			Message optimistic=Message.create(MessageType.LATTICE_VALUE,payload);
			assertNull(optimistic.getRequestID());
			assertTrue(peer.trySend(optimistic));
			assertEquals(CVMLong.create(42),announced.get(5,TimeUnit.SECONDS));
			assertEquals(CVMLong.create(42),maxNodeServer.getLocalValue());
		}
	}

	/** A correlated lattice update must fail promptly when its merge is rejected. */
	@Test
	public void testRejectedLatticeValueReturnsError() throws Exception {
		maxNodeServer = new NodeServer<>(MaxLattice.create(), store);
		allowSingleGroupInbound(maxNodeServer);
		maxNodeServer.launch();

		try (ConvexRemote convex = ConvexRemote.connect(maxNodeServer.getHostAddress())) {
			CVMLong mergeID = CVMLong.create(83);
			AVector<?> payload = Vectors.create(MessageTag.LATTICE_VALUE, mergeID,
				Vectors.empty(), Strings.create("not-an-integer"));
			Result result = convex.message(Message.create(MessageType.LATTICE_VALUE, payload))
				.get(5, TimeUnit.SECONDS);

			assertEquals(mergeID, result.getID());
			assertTrue(result.isError());
			assertEquals(CVMLong.ZERO, maxNodeServer.getLocalValue());
		}
	}

	/** A freshly launched node publishes its initial lattice value immediately. */
	@Test
	public void testLatticeQueryImmediatelyAfterLaunch() throws Exception {
		maxNodeServer = new NodeServer<>(MaxLattice.create(), store);
		allowSingleGroupInbound(maxNodeServer);
		maxNodeServer.launch();

		try (ConvexRemote convex = ConvexRemote.connect(maxNodeServer.getHostAddress())) {
			CVMLong queryId = CVMLong.create(3);
			AVector<?> payload = Vectors.create(MessageTag.LATTICE_QUERY, queryId, Vectors.empty());
			Result result = convex.message(Message.create(MessageType.LATTICE_QUERY, payload))
				.get(5, TimeUnit.SECONDS);

			assertFalse(result.isError());
			assertEquals(CVMLong.ZERO, result.getValue(),
				"initial lattice value should be queryable without an application sync");
		}
	}

	/** A valid replay is accepted but must not force another root write. */
	@Test
	public void testIncomingNoOpReplayDoesNotPersistAgain() throws Exception {
		CountingMemoryStore testStore = new CountingMemoryStore();
		try (NodeServer<AInteger> node = new NodeServer<>(MaxLattice.create(), testStore)) {
			allowSingleGroupInbound(node);
			node.launch();
			int launchWrites = testStore.rootWrites.get();
			LatticePropagator propagator = propagator(node);
			try (ConvexRemote convex = ConvexRemote.connect(node.getHostAddress())) {
				AVector<?> payload = Vectors.create(
					MessageTag.LATTICE_VALUE, CVMLong.create(82), Vectors.empty(), CVMLong.create(42));
				Message value = Message.create(MessageType.LATTICE_VALUE, payload);

				CompletableFuture<ACell> firstAnnounce = propagator.nextAnnounce();
				assertFalse(convex.message(value).get(10, TimeUnit.SECONDS).isError());
				firstAnnounce.get(10, TimeUnit.SECONDS);
				assertEquals(launchWrites + 1, testStore.rootWrites.get());

				CompletableFuture<ACell> replayAnnounce = propagator.nextAnnounce();
				assertFalse(convex.message(value).get(10, TimeUnit.SECONDS).isError());
				assertEquals(launchWrites + 1, testStore.rootWrites.get());
				assertFalse(replayAnnounce.isDone());
			}
		}
	}

	/** An inbound sync failure leaves memory advanced without imposing shutdown policy. */
	@Test
	public void testInboundPersistenceFailureLeavesPolicyToOperator() throws Exception {
		FailingMemoryStore testStore = new FailingMemoryStore();
		NodeServer<AInteger> node = new NodeServer<>(MaxLattice.create(), testStore);
		try {
			LatticePropagator endpoint=propagator(node);
			node.launch();
			ACell durableBefore = testStore.getRootData();
			assertNotNull(durableBefore, "launch should establish the initial durable snapshot");
			testStore.failRootWrites = true;

			endpoint.handleIncomingMessage(latticeValue(CVMLong.create(42), null));

			assertEquals(CVMLong.create(42), node.getLocalValue(),
				"successful merge remains visible in memory");
			assertSame(durableBefore, testStore.getRootData(),
				"injected pre-write failure must not advance the persisted root");
			assertTrue(node.isRunning(), "durability recovery policy belongs to the operator");
		} finally {
			testStore.failRootWrites = false;
			node.close();
			testStore.close();
		}
	}

	/**
	 * Control test: cursor.set + explicit sync() relays to the propagator.
	 *
	 * <p>Verifies that the propagation infrastructure works correctly when
	 * triggered via the local write path. This proves that any failure in
	 * {@link #testIncomingMergeRelayedToPropagator} would be due to a missing
	 * sync in the incoming message path, not a broken propagator.
	 */
	@Test
	public void testExplicitSyncRelaysToPropagator() throws Exception {
		ALattice<AInteger> lattice = MaxLattice.create();
		AStore testStore = new MemoryStore();

		NodeConfig cfg = NodeConfig.create(Maps.of(
			NodeConfig.PORT, CVMLong.create(-1)
		));
		NodeServer<AInteger> node = new NodeServer<>(lattice, testStore, cfg);

		try {
			LatticePropagator propagator=propagator(node);
			node.launch();
			CompletableFuture<ACell> announced=propagator.nextAnnounce();

			// Local write path: set value directly on cursor, then sync explicitly.
			// This is how application code drives the node — sync() is the caller's
			// responsibility (unlike the incoming message path which syncs internally).
			// Node commit is synchronous; propagation-group materialisation is not.
			node.getCursor().set(CVMLong.create(42));
			node.getCursor().sync();
			assertEquals(CVMLong.create(42),announced.get(5,TimeUnit.SECONDS));

			// Explicit sync should always work — this is the control
			assertNotNull(propagator.getLastAnnouncedValue(),
				"Propagator should have announced value after explicit sync");
			assertEquals(CVMLong.create(42), propagator.getLastAnnouncedValue(),
				"Propagator's announced value should match synced value");
		} finally {
			node.close();
			testStore.close();
		}
	}

	// ===== LatticeConnectionManager tests =====

	/**
	 * Test identity-keyed peer connection: addPeer(AccountKey, Convex),
	 * getConnection, isConnected, removePeer.
	 */
	@Test
	public void testKeyedPeerConnection() throws Exception {
		ALattice<AInteger> lattice = MaxLattice.create();
		maxNodeServer = new NodeServer<>(lattice, store);
		propagator(maxNodeServer);
		maxNodeServer.launch();

		InetSocketAddress addr = maxNodeServer.getHostAddress();
		ConvexRemote peer = ConvexRemote.connect(addr);

		LatticeConnectionManager cm = propagator(maxNodeServer).getConnectionManager();
		AccountKey peerKey = AKeyPair.generate().getAccountKey();

		try {
			// Add peer with identity
			cm.addPeer(peerKey, peer);
			assertTrue(cm.isConnected(peerKey), "Peer should be connected after addPeer");
			assertEquals(peer, cm.getConnection(peerKey), "getConnection should return the peer");
			assertEquals(1, cm.getConnectionCount(), "Should have 1 connection");

			// Verify the peer appears in getPeers()
			assertTrue(cm.getPeers().contains(peer), "getPeers should include the connection");

			// Verify the peer appears in getDesiredPeers()
			assertTrue(cm.getDesiredPeers().containsKey(peerKey),
				"Desired peers should include the peer");

			// Remove peer
			cm.removePeer(peerKey);
			assertFalse(cm.isConnected(peerKey), "Peer should not be connected after removePeer");
			assertNull(cm.getConnection(peerKey), "getConnection should return null after remove");
			assertEquals(0, cm.getConnectionCount(), "Should have 0 connections");
			assertFalse(cm.getDesiredPeers().containsKey(peerKey),
				"Desired peers should not include removed peer");
		} finally {
			peer.close();
		}
	}

	/**
	 * Outbound peer connections start at the public cap and receive the larger tier
	 * only when the live endpoint has proved the AccountKey used for its manager slot.
	 */
	@Test
	public void testVerifiedPeerReceivesTrustedMessageLimit() throws Exception {
		AKeyPair serverKey = AKeyPair.generate();
		AKeyPair clientKey = AKeyPair.generate();
		NodeConfig cfg = NodeConfig.create(Maps.of(
			NodeConfig.MAX_MESSAGE_SIZE, CVMLong.create(4096),
			NodeConfig.MAX_TRUSTED_MESSAGE_SIZE, CVMLong.create(8 * 1024 * 1024)));

		maxNodeServer = new NodeServer<>(MaxLattice.create(), store, cfg);
		LatticePropagator serverPropagator=unattachedPropagator(maxNodeServer);
		serverPropagator.setTransportKeyPair(serverKey);
		maxNodeServer.addPropagator(serverPropagator);
		maxNodeServer.setInboundPropagatorSelector(connection -> serverPropagator);
		maxNodeServer.launch();

		LatticeConnectionManager cm=serverPropagator.getConnectionManager();
		// Model a distinct outbound node connecting to this test server.
		cm.setKeyPair(clientKey);
		ConvexRemote verified = ConvexRemote.connect(maxNodeServer.getHostAddress(), 4096);
		try {
			assertEquals(4096, verified.getMaxInboundMessageLength());
			CompletableFuture<Convex> admitted = cm.addPeer(serverKey.getAccountKey(), verified);
			assertSame(verified, admitted.get(5, TimeUnit.SECONDS));
			assertTrue(cm.isConnected(serverKey.getAccountKey()));
			assertEquals(8 * 1024 * 1024, verified.getMaxInboundMessageLength(),
				"a connection verified for its manager slot should receive the trusted tier");
		} finally {
			cm.removePeer(serverKey.getAccountKey());
			verified.close();
		}
	}

	/** A bootstrap connection remains capability-free until its challenge resolves. */
	@Test
	public void testPeerChallengeLimboAndPromotion() throws Exception {
		LatticeConnectionManager cm = new LatticeConnectionManager(store);
		cm.setKeyPair(AKeyPair.generate());
		AccountKey peerKey = AKeyPair.generate().getAccountKey();
		ControlledVerificationConvex peer = new ControlledVerificationConvex();

		CompletableFuture<Convex> admission = cm.addPeer(peerKey, peer);
		assertTrue(cm.isVerificationPending(peerKey));
		assertEquals(1, cm.getPendingConnectionCount());
		assertFalse(cm.isConnected(peerKey));
		assertFalse(cm.getPeers().contains(peer));
		assertNull(peer.getStore(), "limbo must not grant reverse store access");

		peer.completeVerification(peerKey);
		assertSame(peer, admission.get(5, TimeUnit.SECONDS));
		assertFalse(cm.isVerificationPending(peerKey));
		assertEquals(0, cm.getPendingConnectionCount());
		assertSame(peer, cm.getConnection(peerKey));
		assertSame(store, peer.getStore(), "promotion should grant the configured store capability");
		cm.close();
	}

	/** A failed bootstrap challenge closes the socket without admitting it. */
	@Test
	public void testFailedPeerChallengeNeverEntersActiveSet() throws Exception {
		LatticeConnectionManager cm = new LatticeConnectionManager(store);
		cm.setKeyPair(AKeyPair.generate());
		AccountKey peerKey = AKeyPair.generate().getAccountKey();
		ControlledVerificationConvex peer = new ControlledVerificationConvex();

		CompletableFuture<Convex> admission = cm.addPeer(peerKey, peer);
		peer.failVerification();
		assertFalse(cm.isVerificationPending(peerKey));
		assertTrue(admission.isCompletedExceptionally());
		assertThrows(ExecutionException.class, () -> admission.get(5, TimeUnit.SECONDS));
		assertFalse(cm.isConnected(peerKey));
		assertFalse(cm.getPeers().contains(peer));
		assertNull(peer.getStore());
		assertFalse(peer.isConnected());
		cm.close();
	}

	/** An inbound socket becomes an outbound route only after both trust and admission. */
	@Test
	public void testInboundRouteUpgradeRequiresAuthenticatedAdmittedIdentity() throws Exception {
		LatticeConnectionManager cm = new LatticeConnectionManager(store);
		AccountKey peerKey = AKeyPair.generate().getAccountKey();
		RecordingConnection inbound = new RecordingConnection();

		assertThrows(SecurityException.class, () -> cm.upgradeInboundConnection(inbound),
			"operator-visible inbound access must not imply authenticated route trust");
		assertFalse(cm.hasUpgradedInboundConnection(peerKey));

		inbound.setTrustedKey(peerKey);
		assertThrows(SecurityException.class, () -> cm.upgradeInboundConnection(inbound),
			"a proven but unadmitted key must not become a propagation route");

		cm.addPeer(peerKey);
		CompletableFuture<AConnection> upgraded = cm.whenInboundConnectionUpgraded(peerKey);
		assertSame(inbound, cm.upgradeInboundConnection(inbound));
		assertSame(inbound, upgraded.get(5, TimeUnit.SECONDS));
		assertTrue(cm.hasUpgradedInboundConnection(peerKey));
		assertFalse(cm.isConnected(peerKey),
			"the upgraded inbound route must remain distinct from outbound Convex clients");
		assertEquals(1, cm.getPropagationRouteCount());

		cm.broadcast(Message.createPing(1));
		assertEquals(1, inbound.sent.get());
		cm.removeUpgradedInboundConnection(inbound);
		assertFalse(cm.hasUpgradedInboundConnection(peerKey));
		assertEquals(0, cm.getPropagationRouteCount());
		cm.close();
	}

	/**
	 * Test that addPeer(AccountKey) adds a desired peer with no connection,
	 * and addPeer(AccountKey, InetSocketAddress) creates a desired peer with transport.
	 */
	@Test
	public void testDesiredPeerWithoutConnection() throws Exception {
		ALattice<AInteger> lattice = MaxLattice.create();
		maxNodeServer = new NodeServer<>(lattice, store, NodeConfig.port(-1));
		propagator(maxNodeServer);
		maxNodeServer.launch();

		LatticeConnectionManager cm = propagator(maxNodeServer).getConnectionManager();
		AccountKey key1 = AKeyPair.generate().getAccountKey();
		AccountKey key2 = AKeyPair.generate().getAccountKey();

		// addPeer(AccountKey) — no transport, no connection
		cm.addPeer(key1);
		assertTrue(cm.getDesiredPeers().containsKey(key1), "key1 should be in desired peers");
		assertFalse(cm.isConnected(key1), "key1 should not be connected (no transport)");
		assertNull(cm.getDesiredPeers().get(key1).transports,
			"key1 should have null transports");

		// addPeer(AccountKey, InetSocketAddress) — has transport, no live connection yet
		InetSocketAddress fakeAddr = new InetSocketAddress("localhost", 19999);
		cm.addPeer(key2, fakeAddr);
		assertTrue(cm.getDesiredPeers().containsKey(key2), "key2 should be in desired peers");
		assertFalse(cm.isConnected(key2), "key2 should not be connected yet");
		assertNotNull(cm.getDesiredPeers().get(key2).transports,
			"key2 should have transport info");

		// Clean up
		cm.removePeer(key1);
		cm.removePeer(key2);
	}

	/** Discovery and explicit additions share one hard desired-peer bound. */
	@Test
	public void testDesiredPeerLimit() throws Exception {
		NodeConfig cfg=NodeConfig.create(Maps.of(
			NodeConfig.MAX_DESIRED_PEERS,CVMLong.create(2)));
		maxNodeServer=new NodeServer<>(MaxLattice.create(),store,cfg);
		propagator(maxNodeServer);
		maxNodeServer.launch();
		LatticeConnectionManager cm=propagator(maxNodeServer).getConnectionManager();
		assertEquals(2,cm.getMaxDesiredPeers());

		for (int i=0; i<3; i++) {
			cm.updateDiscoveredPeer(AKeyPair.generate().getAccountKey(),Vectors.empty(),i+1);
		}
		assertEquals(2,cm.getDesiredPeers().size());

		CompletableFuture<Convex> rejected=cm.connectPeer(
			AKeyPair.generate().getAccountKey(),new InetSocketAddress("localhost",1));
		assertThrows(ExecutionException.class,
			() -> rejected.get(5,TimeUnit.SECONDS));
		assertEquals(2,cm.getDesiredPeers().size());
	}

	/**
	 * Test that a dead connection is detected and pruned, and the desired
	 * peer entry survives for reconnection.
	 */
	@Test
	public void testDeadConnectionPruning() throws Exception {
		ALattice<AInteger> lattice = MaxLattice.create();
		maxNodeServer = new NodeServer<>(lattice, store);
		propagator(maxNodeServer);
		maxNodeServer.launch();

		InetSocketAddress addr = maxNodeServer.getHostAddress();
		ConvexRemote peer = ConvexRemote.connect(addr);

		LatticeConnectionManager cm = propagator(maxNodeServer).getConnectionManager();
		AccountKey peerKey = AKeyPair.generate().getAccountKey();

		cm.addPeer(peerKey, peer);
		assertTrue(cm.isConnected(peerKey), "Should be connected initially");

		// Kill the connection
		peer.close();

		// isConnected should detect the dead connection and prune it
		assertFalse(cm.isConnected(peerKey), "Should detect dead connection");
		assertEquals(0, cm.getConnectionCount(), "Dead connection should be pruned");

		// Desired peer should still exist (for reconnection)
		assertTrue(cm.getDesiredPeers().containsKey(peerKey),
			"Desired peer should survive connection death");
	}

	/**
	 * Test backoff calculation produces values in expected range.
	 */
	@Test
	public void testBackoffCalculation() {
		// First failure: should be in [500, 1000] ms (base=1000, half+jitter)
		for (int i = 0; i < 10; i++) {
			long backoff = LatticeConnectionManager.calculateBackoff(1);
			assertTrue(backoff >= 500 && backoff <= 1000,
				"First backoff should be in [500,1000], got " + backoff);
		}

		// Many failures: should cap at MAX_BACKOFF (30s)
		for (int i = 0; i < 10; i++) {
			long backoff = LatticeConnectionManager.calculateBackoff(20);
			assertTrue(backoff >= 15000 && backoff <= 30000,
				"Max backoff should be in [15000,30000], got " + backoff);
		}
	}

	/**
	 * Test transport resolution from DesiredPeer entries.
	 */
	@Test
	public void testTransportResolution() {
		AccountKey key = AKeyPair.generate().getAccountKey();

		// TCP URI resolves
		InetSocketAddress addr = new InetSocketAddress("localhost", 18888);
		LatticeConnectionManager.DesiredPeer dp = LatticeConnectionManager.DesiredPeer.create(key, addr);
		InetSocketAddress resolved = LatticeConnectionManager.resolveTransport(dp);
		assertNotNull(resolved, "TCP transport should resolve");
		assertEquals(18888, resolved.getPort());

		// No transports → null
		LatticeConnectionManager.DesiredPeer empty = LatticeConnectionManager.DesiredPeer.create(key);
		assertNull(LatticeConnectionManager.resolveTransport(empty),
			"Null transports should return null");
	}

	// ===== Challenge/Response verification tests =====

	/**
	 * Test that verifyPeer succeeds when the selected propagation endpoint has a key
	 * and the challenge is addressed to the correct key.
	 */
	@Test
	public void testChallengeResponse() throws Exception {
		AKeyPair serverKP = AKeyPair.generate();
		AKeyPair clientKP = AKeyPair.generate();

		ALattice<AInteger> lattice = MaxLattice.create();
		maxNodeServer = new NodeServer<>(lattice, store);
		LatticePropagator serverPropagator=unattachedPropagator(maxNodeServer);
		serverPropagator.setTransportKeyPair(serverKP);
		maxNodeServer.addPropagator(serverPropagator);
		maxNodeServer.setInboundPropagatorSelector(connection -> serverPropagator);
		maxNodeServer.launch();

		InetSocketAddress addr = maxNodeServer.getHostAddress();
		ConvexRemote convex = Convex.connect(addr, null, clientKP);

		try {
			AccountKey result = convex.verifyPeer(
				serverKP.getAccountKey(),Message.LATTICE_PEER_CHALLENGE_CONTEXT)
				.get(5, TimeUnit.SECONDS);
			assertEquals(serverKP.getAccountKey(), result, "Should return server key");
			assertEquals(serverKP.getAccountKey(), convex.getVerifiedPeer());
		} finally {
			convex.close();
			assertNull(convex.getVerifiedPeer(), "Should be cleared on close");
		}
	}

	/**
	 * Test that verifyPeer with null expectedKey discovers the remote key.
	 */
	@Test
	public void testChallengeResponseDiscovery() throws Exception {
		AKeyPair serverKP = AKeyPair.generate();
		AKeyPair clientKP = AKeyPair.generate();

		ALattice<AInteger> lattice = MaxLattice.create();
		maxNodeServer = new NodeServer<>(lattice, store);
		LatticePropagator serverPropagator=unattachedPropagator(maxNodeServer);
		serverPropagator.setTransportKeyPair(serverKP);
		maxNodeServer.addPropagator(serverPropagator);
		maxNodeServer.setInboundPropagatorSelector(connection -> serverPropagator);
		maxNodeServer.launch();

		InetSocketAddress addr = maxNodeServer.getHostAddress();
		ConvexRemote convex = Convex.connect(addr, null, clientKP);

		try {
			// null expectedKey — accept whoever responds
			AccountKey result = convex.verifyPeer(
				null,Message.LATTICE_PEER_CHALLENGE_CONTEXT).get(5, TimeUnit.SECONDS);
			assertEquals(serverKP.getAccountKey(), result, "Should discover server key");
		} finally {
			convex.close();
		}
	}

	/**
	 * Test that verifyPeer fails when the expected key does not match
	 * the propagation endpoint's actual key.
	 */
	@Test
	public void testChallengeResponseWrongKey() throws Exception {
		AKeyPair serverKP = AKeyPair.generate();
		AKeyPair clientKP = AKeyPair.generate();
		AKeyPair wrongKP = AKeyPair.generate();

		ALattice<AInteger> lattice = MaxLattice.create();
		maxNodeServer = new NodeServer<>(lattice, store);
		LatticePropagator serverPropagator=unattachedPropagator(maxNodeServer);
		serverPropagator.setTransportKeyPair(serverKP);
		maxNodeServer.addPropagator(serverPropagator);
		maxNodeServer.setInboundPropagatorSelector(connection -> serverPropagator);
		maxNodeServer.launch();

		InetSocketAddress addr = maxNodeServer.getHostAddress();
		ConvexRemote convex = Convex.connect(addr, null, clientKP);

		try {
			AccountKey result = convex.verifyPeer(
				wrongKP.getAccountKey(),Message.LATTICE_PEER_CHALLENGE_CONTEXT)
				.get(5, TimeUnit.SECONDS);
			assertNull(result, "verifyPeer should fail for wrong server key");
			assertNull(convex.getVerifiedPeer());
		} finally {
			convex.close();
		}
	}

	/**
	 * Test that an application signing key is not implicitly used as the transport
	 * identity. Transport authentication must be configured explicitly.
	 */
	@Test
	public void testChallengeResponseNoTransportKey() throws Exception {
		AKeyPair applicationKP = AKeyPair.generate();
		AKeyPair clientKP = AKeyPair.generate();

		ALattice<AInteger> lattice = MaxLattice.create();
		maxNodeServer = new NodeServer<>(lattice, store);
		maxNodeServer.setMergeContext(LatticeContext.create(null, applicationKP));
		// Deliberately no setTransportKeyPair: lattice signing authority must not
		// leak into the connection-authentication role.
		allowSingleGroupInbound(maxNodeServer);
		maxNodeServer.launch();

		InetSocketAddress addr = maxNodeServer.getHostAddress();
		ConvexRemote convex = Convex.connect(addr, null, clientKP);

		try {
			AccountKey result = convex.verifyPeer(
				AKeyPair.generate().getAccountKey(),Message.LATTICE_PEER_CHALLENGE_CONTEXT)
				.get(5, TimeUnit.SECONDS);
			assertNull(result, "verifyPeer should fail when server has no signing key");
		} finally {
			convex.close();
		}
	}

	/**
	 * The propagation endpoint signs only the lattice-route domain.
	 */
	@Test
	public void testChallengeResponseWithContext() throws Exception {
		AKeyPair serverKP = AKeyPair.generate();
		AKeyPair clientKP = AKeyPair.generate();
		ACell contextID = Strings.create("test-lattice-v1");

		ALattice<AInteger> lattice = MaxLattice.create();
		maxNodeServer = new NodeServer<>(lattice, store);
		LatticePropagator serverPropagator=unattachedPropagator(maxNodeServer);
		serverPropagator.setTransportKeyPair(serverKP);
		maxNodeServer.addPropagator(serverPropagator);
		maxNodeServer.setInboundPropagatorSelector(connection -> serverPropagator);
		maxNodeServer.launch();

		InetSocketAddress addr = maxNodeServer.getHostAddress();
		ConvexRemote convex = Convex.connect(addr, null, clientKP);

		try {
			AccountKey result = convex.verifyPeer(
				serverKP.getAccountKey(), contextID).get(5, TimeUnit.SECONDS);
			assertNull(result, "endpoint must reject a different challenge context");
		} finally {
			convex.close();
		}
	}

	/** Deterministic connection whose identity challenge is completed by the test. */
	private static final class ControlledVerificationConvex extends Convex {
		private final CompletableFuture<AccountKey> verification = new CompletableFuture<>();
		private volatile boolean connected = true;

		ControlledVerificationConvex() {
			super(null, null);
		}

		void completeVerification(AccountKey peerKey) {
			setVerifiedPeer(peerKey);
			verification.complete(peerKey);
		}

		void failVerification() {
			verification.completeExceptionally(new SecurityException("Challenge rejected"));
		}

		@Override
		public CompletableFuture<AccountKey> verifyPeer(AccountKey expectedKey) {
			return verification;
		}

		@Override
		public CompletableFuture<AccountKey> verifyPeer(AccountKey expectedKey, ACell contextID) {
			assertEquals(Message.LATTICE_PEER_CHALLENGE_CONTEXT,contextID);
			return verification;
		}

		@Override
		public boolean isConnected() {
			return connected;
		}

		@Override
		public CompletableFuture<Result> transact(SignedData<ATransaction> signedTransaction) {
			return CompletableFuture.completedFuture(Result.SENT_MESSAGE);
		}

		@Override
		public CompletableFuture<Result> messageRaw(Blob message) {
			return CompletableFuture.completedFuture(Result.SENT_MESSAGE);
		}

		@Override
		public CompletableFuture<Result> message(Message message) {
			return CompletableFuture.completedFuture(Result.SENT_MESSAGE);
		}

		@Override
		public <T extends ACell> CompletableFuture<T> acquire(Hash hash, AStore targetStore) {
			return new CompletableFuture<>();
		}

		@Override
		public CompletableFuture<Result> requestStatus() {
			return CompletableFuture.completedFuture(Result.SENT_MESSAGE);
		}

		@Override
		protected CompletableFuture<Result> sendChallenge(SignedData<ACell> data) {
			return CompletableFuture.completedFuture(Result.SENT_MESSAGE);
		}

		@Override
		public CompletableFuture<Result> query(ACell query, Address address) {
			return CompletableFuture.completedFuture(Result.SENT_MESSAGE);
		}

		@Override
		public void close() {
			connected = false;
			verifiedPeer = null;
		}

		@Override
		public String toString() {
			return "Controlled verification connection";
		}

		@Override
		public InetSocketAddress getHostAddress() {
			return null;
		}

		@Override
		public void reconnect() {
			connected = true;
		}
	}
}
