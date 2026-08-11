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
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import convex.core.ErrorCodes;
import convex.core.Result;
import convex.core.crypto.AKeyPair;
import convex.core.cvm.Keywords;
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
import convex.lattice.P2PLattice;
import convex.lattice.cursor.ACursor;
import convex.lattice.cursor.PathCursor;
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

	/** Explicit public test policy: every network connection uses the primary view. */
	private static void allowPrimaryInbound(NodeServer<?> node) {
		node.setInboundPropagatorSelector(connection -> node.getPropagator());
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
		assertTrue(maxNodeServer.withinInboundSizeLimit(CVMLong.ONE));

		// A large value exceeds the limit and is rejected
		convex.core.data.ABlob big = convex.core.data.Blob.wrap(new byte[500]);
		assertTrue(big.getMemorySize() > 100); // sanity: this value really is over the limit
		assertFalse(maxNodeServer.withinInboundSizeLimit(big));

		// Under the default (permissive) config, the same large value is accepted
		NodeServer<AInteger> permissive = new NodeServer<>(lattice, store);
		try {
			assertTrue(permissive.withinInboundSizeLimit(big));
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
		assertEquals(convex.core.cpos.CPoSConstants.MAX_MESSAGE_LENGTH,
			defaults.getMaxTrustedMessageSize());
		assertEquals(defaults.getMaxMessageSize(), defaults.getMaxInboundValueSize());
		assertEquals(256, defaults.getMaxConnections());
		assertEquals(1024, defaults.getInboundQueueSize());
		assertEquals(10_000L, defaults.getInboundShutdownTimeout());

		NodeConfig configured = NodeConfig.create(Maps.of(
			NodeConfig.MAX_MESSAGE_SIZE, CVMLong.create(8192),
			NodeConfig.MAX_TRUSTED_MESSAGE_SIZE, CVMLong.create(65536),
			NodeConfig.MAX_CONNECTIONS, CVMLong.create(12),
			NodeConfig.INBOUND_QUEUE_SIZE, CVMLong.create(34),
			NodeConfig.INBOUND_SHUTDOWN_TIMEOUT, CVMLong.create(56)));
		assertEquals(8192, configured.getMaxMessageSize());
		assertEquals(65536, configured.getMaxTrustedMessageSize());
		assertEquals(12, configured.getMaxConnections());
		assertEquals(34, configured.getInboundQueueSize());
		assertEquals(56, configured.getInboundShutdownTimeout());

		NodeConfig invalid = NodeConfig.create(Maps.of(
			NodeConfig.INBOUND_QUEUE_SIZE, CVMLong.ZERO));
		assertThrows(IllegalArgumentException.class, invalid::getInboundQueueSize);
		NodeConfig invalidTimeout = NodeConfig.create(Maps.of(
			NodeConfig.INBOUND_SHUTDOWN_TIMEOUT, CVMLong.ZERO));
		assertThrows(IllegalArgumentException.class, invalidTimeout::getInboundShutdownTimeout);
		NodeServer<AInteger> invalidTimeoutNode =
			new NodeServer<>(MaxLattice.create(), store, invalidTimeout);
		assertThrows(IllegalArgumentException.class, invalidTimeoutNode::launch,
			"invalid close policy must be rejected before any service starts");

		NodeConfig aboveProtocolMaximum = NodeConfig.create(Maps.of(
			NodeConfig.MAX_TRUSTED_MESSAGE_SIZE,
			CVMLong.create(convex.core.cpos.CPoSConstants.MAX_MESSAGE_LENGTH + 1)));
		assertThrows(IllegalArgumentException.class, aboveProtocolMaximum::getMaxTrustedMessageSize);
	}

	/** Records the thread used for a real MaxLattice merge. */
	static final class RecordingLattice extends ALattice<AInteger> {
		private final ALattice<AInteger> delegate = MaxLattice.create();
		volatile String mergeThread;

		@Override public AInteger merge(AInteger ownValue, AInteger otherValue) {
			mergeThread = Thread.currentThread().getName();
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
		maxNodeServer.launch();

		LatticePropagator propagator = maxNodeServer.getPropagator();
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
		allowPrimaryInbound(maxNodeServer);
		maxNodeServer.launch();

		// Create a Convex connection to the server
		InetSocketAddress serverAddress = maxNodeServer.getHostAddress();
		ConvexRemote peer = ConvexRemote.connect(serverAddress);
		
		try {
			// Pull from peer - should get the current value (zero initially)
			CompletableFuture<AInteger> future = maxNodeServer.pull(peer);
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
		allowPrimaryInbound(maxNodeServer);
		
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
	public void testPublicSinglePropagatorSharesPrimaryStore() throws Exception {
		Blob branch = Blobs.createRandom(400);
		ASet<ACell> expected = Sets.of(branch);
		setNodeServer = new NodeServer<>(SetLattice.create(), store);
		allowPrimaryInbound(setNodeServer);
		setNodeServer.launch();

		LatticePropagator propagator = setNodeServer.getPropagator();
		assertNotNull(propagator);
		assertSame(store, propagator.getStore(),
			"the default propagator should share the authoritative cursor store");

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
			Result result = convex.message(request).get(5, TimeUnit.SECONDS);

			assertEquals(ErrorCodes.TRUST, result.getErrorCode());
			assertEquals(CVMLong.create(71), result.getID());
		}
	}

	/** An assigned listener connection reads only its selected propagator store. */
	@Test
	public void testInboundDataRequestUsesSelectedPropagatorStore() throws Exception {
		try (AStore primaryStore = new MemoryStore();
				AStore publicStore = new MemoryStore()) {
			Blob privateValue = Cells.persist(Blobs.createRandom(400), primaryStore);
			Blob publicValue = Cells.persist(Blobs.createRandom(400), publicStore);
			LatticePropagator primary = new LatticePropagator(primaryStore);
			LatticePropagator publicPropagator = new LatticePropagator(publicStore);

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
			Result result = convex.message(Message.create(MessageType.LATTICE_QUERY, payload))
				.get(5, TimeUnit.SECONDS);

			assertEquals(ErrorCodes.TRUST, result.getErrorCode());
			assertEquals(CVMLong.create(73), result.getID());
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
	 * A partial lattice value is acquired entirely in its owning ingress
	 * propagator store. The authoritative cursor and primary store remain
	 * untouched until acquisition is complete and the ordered merge is accepted.
	 */
	@Test
	public void testIncompleteValueAcquiredBeforePrimaryMerge() throws Exception {
		try (AStore primaryStore = new MemoryStore();
				AStore ingressStore = new MemoryStore();
				BlockingReadMemoryStore sourceStore = new BlockingReadMemoryStore()) {
			Blob missingBranch = Blobs.createRandom(400);
			ASet<ACell> remoteValue = Sets.of(missingBranch);
			remoteValue = Cells.persist(remoteValue, sourceStore);
			sourceStore.blockedHash = missingBranch.getHash();

			LatticePropagator primary = new LatticePropagator(primaryStore);
			LatticePropagator ingress = new LatticePropagator(ingressStore);
			try (NodeServer<ASet<ACell>> receiver = new NodeServer<>(SetLattice.create(), primaryStore)) {
				receiver.addPropagator(primary);
				receiver.addPropagator(ingress);
				receiver.setInboundPropagatorSelector(connection -> ingress);
				receiver.launch();

				LatticeConnectionManager sourceManager = new LatticeConnectionManager(sourceStore);
				ConvexRemote source = ConvexRemote.connect(receiver.getHostAddress());
				try {
					sourceManager.addPeer(AKeyPair.generate().getAccountKey(), source);
					CompletableFuture<ACell> primaryAnnounce = primary.nextAnnounce();

					CVMLong mergeID = CVMLong.create(80);
					AVector<?> payload = Vectors.create(
						MessageTag.LATTICE_VALUE, mergeID, Vectors.empty(), remoteValue);
					// Encode only the protocol root. The large Blob remains an indirect
					// reference and must be requested from the source propagator store.
					Message partial = Message.create(
						MessageType.LATTICE_VALUE, payload, payload.getEncoding());
					CompletableFuture<Result> mergeResult = source.message(partial);

					assertTrue(sourceStore.requested.await(5, TimeUnit.SECONDS),
						"receiver should request the missing branch on the same connection");
					assertFalse(mergeResult.isDone(),
						"LATTICE_VALUE result must wait for acquisition and merge");
					assertTrue(receiver.getLocalValue().isEmpty(),
						"partial data must never reach the lattice cursor");
					assertNull(primaryStore.refForHash(missingBranch.getHash()),
						"acquisition must not deposit unvalidated data in the primary store");
					assertNull(ingressStore.refForHash(missingBranch.getHash()),
						"the withheld branch should not appear before its response arrives");

					sourceStore.release.countDown();
					Result result = mergeResult.get(5, TimeUnit.SECONDS);
					assertEquals(mergeID, result.getID());
					assertFalse(result.isError());
					assertNull(result.getValue(), "successful merge acknowledgement should be empty");
					assertEquals(remoteValue, primaryAnnounce.get(5, TimeUnit.SECONDS));
					assertEquals(remoteValue, receiver.getLocalValue());
					assertNotNull(ingressStore.refForHash(missingBranch.getHash()),
						"complete acquisition belongs to the ingress propagator store");
					assertNotNull(primaryStore.refForHash(missingBranch.getHash()),
						"only the accepted merged root may enter the primary checkpoint");
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
			ingressStore.blockedHash = remoteValue.getHash();

			NodeConfig config = NodeConfig.create(Maps.of(
				NodeConfig.PORT, CVMLong.ZERO,
				NodeConfig.INBOUND_SHUTDOWN_TIMEOUT, CVMLong.create(100)));
			LatticePropagator primary = new LatticePropagator(primaryStore);
			LatticePropagator ingress = new LatticePropagator(ingressStore);
			receiver = new NodeServer<>(SetLattice.create(), primaryStore, config);
			receiver.addPropagator(primary);
			receiver.addPropagator(ingress);
			receiver.setInboundPropagatorSelector(connection -> ingress);
			receiver.launch();

			sourceManager = new LatticeConnectionManager(sourceStore);
			source = ConvexRemote.connect(receiver.getHostAddress());
			sourceManager.addPeer(AKeyPair.generate().getAccountKey(), source);

			AVector<?> payload = Vectors.create(
				MessageTag.LATTICE_VALUE, null, Vectors.empty(), remoteValue);
			Message partial = Message.create(
				MessageType.LATTICE_VALUE, payload, payload.getEncoding());
			assertTrue(source.trySend(partial));
			assertTrue(ingressStore.acquisitionReadEntered.await(5, TimeUnit.SECONDS),
				"the inbound Acquiror should enter the controlled store operation");

			IOException timeout = assertThrows(IOException.class, receiver::close);
			assertTrue(timeout.getMessage().contains("acquisition shutdown incomplete"));
			assertEquals(NodeServer.LifecycleState.STOPPING, receiver.getLifecycleState());
			assertTrue(receiver.getLocalValue().isEmpty(),
				"an acquisition cancelled during shutdown must never merge");
			assertNull(primaryStore.refForHash(missingBranch.getHash()),
				"cancelled acquisition must not expose data to the primary store");

			ingressStore.release();
			assertTrue(ingressStore.acquisitionReadFinished.await(5, TimeUnit.SECONDS));
			receiver.close();
			assertEquals(NodeServer.LifecycleState.STOPPED, receiver.getLifecycleState());
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

	// ===== P2P NodeInfo advertisement tests =====

	/**
	 * Test that a NodeServer with URL and signing key publishes NodeInfo
	 * into the :p2p :nodes lattice on launch.
	 */
	@Test
	public void testNodeInfoPublication() throws IOException, InterruptedException {
		AKeyPair kp = AKeyPair.generate();

		// Config with public URL
		NodeConfig cfg = NodeConfig.create(Maps.of(
			NodeConfig.URL, Strings.create("tcp://peer.example.com:18888"),
			NodeConfig.PORT, CVMLong.create(-1) // local-only, no network binding
		));

		NodeServer<Index<Keyword, ACell>> server =
			new NodeServer<>(Lattice.ROOT, store, cfg);
		server.setMergeContext(LatticeContext.create(null, kp));

		try {
			server.launch();

			// Read :p2p :nodes from cursor
			@SuppressWarnings("unchecked")
			AHashMap<ACell, SignedData<ACell>> nodes =
				(AHashMap<ACell, SignedData<ACell>>) PathCursor.create(
					server.getCursor(),
					new ACell[] { Keywords.P2P, Keywords.NODES }).get();

			assertNotNull(nodes, ":p2p :nodes should be populated");

			AHashMap<Keyword, ACell> info = P2PLattice.getNodeInfo(nodes, kp.getAccountKey());
			assertNotNull(info, "NodeInfo should exist for the server's key");
			assertEquals(Strings.create("tcp://peer.example.com:18888"),
				((AVector<?>) info.get(Keywords.TRANSPORTS)).get(0));
			assertEquals(Strings.create("Convex Lattice Node"), info.get(Keywords.TYPE));
			assertNotNull(info.get(Keywords.VERSION));
			assertNotNull(info.get(Keywords.TIMESTAMP));
		} finally {
			server.close();
		}
	}

	/**
	 * Test that a NodeServer without URL does not publish NodeInfo.
	 */
	@Test
	public void testNoPublicationWithoutURL() throws IOException, InterruptedException {
		AKeyPair kp = AKeyPair.generate();

		// No URL configured
		NodeConfig cfg = NodeConfig.create(Maps.of(
			NodeConfig.PORT, CVMLong.create(-1)
		));

		NodeServer<Index<Keyword, ACell>> server =
			new NodeServer<>(Lattice.ROOT, store, cfg);
		server.setMergeContext(LatticeContext.create(null, kp));

		try {
			server.launch();

			ACell nodes = PathCursor.create(
				server.getCursor(),
				new ACell[] { Keywords.P2P, Keywords.NODES }).get();

			// Should be null (empty/zero) — no publication
			assertTrue(nodes == null || (nodes instanceof AHashMap && ((AHashMap<?,?>) nodes).isEmpty()),
				":p2p :nodes should be empty when no URL is configured");
		} finally {
			server.close();
		}
	}

	/**
	 * Test that a NodeServer without signing key does not publish NodeInfo.
	 */
	@Test
	public void testNoPublicationWithoutKeyPair() throws IOException, InterruptedException {
		// URL configured but no signing key (default EMPTY context)
		NodeConfig cfg = NodeConfig.create(Maps.of(
			NodeConfig.URL, Strings.create("tcp://peer.example.com:18888"),
			NodeConfig.PORT, CVMLong.create(-1)
		));

		NodeServer<Index<Keyword, ACell>> server =
			new NodeServer<>(Lattice.ROOT, store, cfg);
		// mergeContext stays LatticeContext.EMPTY — no signing key

		try {
			server.launch();

			ACell nodes = PathCursor.create(
				server.getCursor(),
				new ACell[] { Keywords.P2P, Keywords.NODES }).get();

			assertTrue(nodes == null || (nodes instanceof AHashMap && ((AHashMap<?,?>) nodes).isEmpty()),
				":p2p :nodes should be empty when no signing key is available");
		} finally {
			server.close();
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
	 * launch() fails fast when a private URL is configured without the allowPrivateURL opt-out,
	 * and succeeds (publishing) once the opt-out is set.
	 */
	@Test
	public void testLaunchRejectsPrivateURL() throws IOException, InterruptedException {
		AKeyPair kp = AKeyPair.generate();

		NodeConfig badCfg = NodeConfig.create(Maps.of(
			NodeConfig.URL, Strings.create("tcp://localhost:18888"),
			NodeConfig.PORT, CVMLong.create(-1)
		));
		NodeServer<Index<Keyword, ACell>> bad = new NodeServer<>(Lattice.ROOT, store, badCfg);
		bad.setMergeContext(LatticeContext.create(null, kp));
		try {
			assertThrows(IllegalStateException.class, bad::launch);
		} finally {
			bad.close();
		}

		// With the opt-out, the same private URL launches and publishes
		NodeConfig okCfg = NodeConfig.create(Maps.of(
			NodeConfig.URL, Strings.create("tcp://localhost:18888"),
			NodeConfig.ALLOW_PRIVATE_URL, convex.core.data.prim.CVMBool.TRUE,
			NodeConfig.PORT, CVMLong.create(-1)
		));
		NodeServer<Index<Keyword, ACell>> ok = new NodeServer<>(Lattice.ROOT, store, okCfg);
		ok.setMergeContext(LatticeContext.create(null, kp));
		try {
			ok.launch();
			ACell nodes = PathCursor.create(
				ok.getCursor(),
				new ACell[] { Keywords.P2P, Keywords.NODES }).get();
			assertNotNull(nodes, ":p2p :nodes should be populated when allowPrivateURL is set");
		} finally {
			ok.close();
		}
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
		LatticePropagator primary = new LatticePropagator(testStore);
		node.addPropagator(primary);
		node.setMergeContext(LatticeContext.EMPTY);

		assertEquals(NodeServer.LifecycleState.NEW, node.getLifecycleState());
		assertThrows(UnsupportedOperationException.class, node.getPropagators()::clear,
			"callers must not be able to reorder or remove the primary propagator");

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
		@Override public boolean sendMessage(Message msg) { return true; }
		@Override public boolean trySendMessage(Message msg) { return true; }
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
		testStore.failOnRootWrite = 2; // initial seed succeeds; NodeInfo checkpoint fails
		NodeConfig cfg = NodeConfig.create(Maps.of(
			NodeConfig.URL, Strings.create("tcp://peer.example.com:18888"),
			NodeConfig.PORT, CVMLong.ZERO));
		NodeServer<Index<Keyword, ACell>> node = new NodeServer<>(Lattice.ROOT, testStore, cfg);
		node.setMergeContext(LatticeContext.create(null, AKeyPair.generate()));

		try {
			assertThrows(StoreException.class, node::launch);
			assertFalse(node.isRunning(), "a failed launch must not report a live node");
			assertEquals(NodeServer.LifecycleState.STOPPED, node.getLifecycleState(),
				"complete launch rollback must publish a retryable stopped state");
			assertFalse(node.deliverIncomingMessage(Message.createPing(1)).test(Message.createPing(1)),
				"failed launch must leave inbound admission disabled");

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
			allowPrimaryInbound(remote);
			maxNodeServer.launch();
			remote.launch();

			remote.getCursor().set(CVMLong.create(5));
			remote.getCursor().sync();
			maxNodeServer.getCursor().set(CVMLong.create(10));
			maxNodeServer.getCursor().sync();

			try (ConvexRemote peer = ConvexRemote.connect(remote.getHostAddress())) {
				assertEquals(CVMLong.create(10), maxNodeServer.pull(peer).get(5, TimeUnit.SECONDS));
			}

			assertEquals(CVMLong.create(10), maxNodeServer.getLocalValue());
			assertEquals(CVMLong.create(10), maxNodeServer.getPropagator().getLastAnnouncedValue(),
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
			node.launch();
			CompletableFuture<ACell> nextAnnounce = node.getPropagator().nextAnnounce();

			assertNull(node.deliverIncomingMessage(new StackOverflowMessage(faulting)));
			assertNull(node.deliverIncomingMessage(latticeValue(CVMLong.create(42), null)));

			assertEquals(CVMLong.create(42), nextAnnounce.get(10, TimeUnit.SECONDS));
			assertTrue(faulting.closed, "the connection responsible for the Error must be closed");
			assertTrue(node.isRunning(), "a recoverable message Error must not stop the node");
		}
	}

	/** A drain timeout must never permit an old and a new dispatcher to overlap. */
	@Test
	public void testCloseCanRetryAfterInboundDrainTimeout() throws Exception {
		NodeConfig cfg = NodeConfig.create(Maps.of(
			NodeConfig.PORT, CVMLong.ZERO,
			NodeConfig.INBOUND_SHUTDOWN_TIMEOUT, CVMLong.create(100)));
		NodeServer<AInteger> node = new NodeServer<>(MaxLattice.create(), store, cfg);
		BlockingMessage blocking = new BlockingMessage();

		try {
			node.launch();
			assertNull(node.deliverIncomingMessage(blocking));
			assertTrue(blocking.entered.await(5, TimeUnit.SECONDS));

			IOException timeout = assertThrows(IOException.class, node::close);
			assertTrue(timeout.getMessage().contains("shutdown incomplete"));
			assertFalse(node.isRunning());
			assertEquals(NodeServer.LifecycleState.STOPPING, node.getLifecycleState());
			assertThrows(IllegalStateException.class,
				() -> node.setMergeContext(LatticeContext.EMPTY),
				"configuration must stay frozen while the old dispatcher is alive");
			assertThrows(IllegalStateException.class,
				() -> node.addPropagator(node.getPropagator()));
			assertThrows(IllegalStateException.class, node::launch,
				"relaunch must not create a second consumer while the old one is alive");

			blocking.release.countDown();
			assertTrue(blocking.decoded.await(5, TimeUnit.SECONDS));
			node.close(); // retry reaps the retained dispatcher and completes final persistence
			assertEquals(NodeServer.LifecycleState.STOPPED, node.getLifecycleState());

			node.launch();
			assertTrue(node.isRunning(), "launch is safe again after shutdown completes");
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
		maxNodeServer.addPropagator(new LatticePropagator(store));
		allowPrimaryInbound(maxNodeServer);

		// Wrong type for MaxLattice<AInteger> — mergeIncoming rejects it
		ACell badValue = convex.core.data.Blob.wrap(new byte[8]);

		// A connection that sends two bad then one good message stays open; the accept resets the streak
		RecordingConnection tracked = new RecordingConnection();
		maxNodeServer.handleIncomingMessage(latticeValue(badValue, tracked));            // reject 1
		maxNodeServer.handleIncomingMessage(latticeValue(badValue, tracked));            // reject 2
		maxNodeServer.handleIncomingMessage(latticeValue(CVMLong.create(42), tracked));  // accept

		NodeServer.ConnectionStats s = maxNodeServer.statsFor(tracked);
		assertEquals(3, s.messagesReceived);
		assertEquals(2, s.mergesRejected);
		assertEquals(1, s.mergesAccepted);
		assertEquals(0, s.consecutiveRejects); // reset by the accept
		assertFalse(tracked.isClosed(), "Accept reset the streak, so the breaker must not trip");

		// A connection sending only bad messages trips the breaker at the limit
		RecordingConnection abusive = new RecordingConnection();
		maxNodeServer.handleIncomingMessage(latticeValue(badValue, abusive)); // 1
		maxNodeServer.handleIncomingMessage(latticeValue(badValue, abusive)); // 2
		assertFalse(abusive.isClosed(), "Below the threshold the connection stays open");
		maxNodeServer.handleIncomingMessage(latticeValue(badValue, abusive)); // 3 == limit -> trip
		assertTrue(abusive.isClosed(), "Connection closed after reaching the consecutive-reject limit");

		// Aggregate stats reflect the surviving tracked connection (the abusive one was pruned on trip)
		NodeServer.InboundStats agg = maxNodeServer.getInboundStats();
		assertTrue(agg.mergesAccepted >= 1);
		assertTrue(agg.mergesRejected >= 2);
	}

	/**
	 * #566: closed connections drain from the stats map via the periodic sweep (so an idle
	 * node cleans up without inbound traffic), and removeConnection is the explicit single-sink
	 * teardown.
	 */
	@Test
	public void testConnectionStatsSweep() {
		maxNodeServer = new NodeServer<>(MaxLattice.create(), store, NodeConfig.port(-1));

		RecordingConnection open = new RecordingConnection();
		RecordingConnection gone = new RecordingConnection();
		maxNodeServer.handleIncomingMessage(latticeValue(CVMLong.create(1), open));
		maxNodeServer.handleIncomingMessage(latticeValue(CVMLong.create(1), gone));
		assertEquals(2, maxNodeServer.getInboundStats().connections);

		// A closed connection is pruned by the sweep; the open one survives
		gone.close();
		maxNodeServer.sweepClosedConnections();
		assertEquals(1, maxNodeServer.getInboundStats().connections);
		assertNotNull(maxNodeServer.statsFor(open));

		// removeConnection is the explicit single-sink teardown
		maxNodeServer.removeConnection(open);
		assertEquals(0, maxNodeServer.getInboundStats().connections);
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
		allowPrimaryInbound(node);

		try {
			node.launch();

			LatticePropagator propagator = node.getPropagator();
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
				assertTrue(announced.isDone(),
					"merge acknowledgement must follow synchronous publication");
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
			allowPrimaryInbound(node);
			node.launch();
			CompletableFuture<ACell> announced = node.getPropagator().nextAnnounce();
			try (ConvexRemote convex = ConvexRemote.connect(node.getHostAddress())) {
				AVector<?> payload = Vectors.create(
					MessageTag.LATTICE_VALUE, null, Vectors.empty(), CVMLong.create(42));
				convex.message(Message.create(MessageType.LATTICE_VALUE, payload));
				announced.get(10, TimeUnit.SECONDS);
			}
			assertEquals("NodeServer inbound dispatcher", lattice.mergeThread);
			assertFalse(lattice.mergeThread.startsWith("convex-netty"));
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
			allowPrimaryInbound(source);
			source.launch();
			target.launch();
			source.getCursor().path(regionA).merge(CVMLong.create(111));
			source.getCursor().path(regionB).merge(CVMLong.create(222));
			source.getCursor().sync();

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
			allowPrimaryInbound(source);
			source.launch();
			target.launch();
			source.getCursor().path(regionA).merge(CVMLong.create(111));
			source.getCursor().path(regionB).merge(CVMLong.create(222));
			source.getCursor().sync();

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
			allowPrimaryInbound(source);
			source.launch();
			target.launch();
			source.getCursor().path(region).merge(expected);
			source.getCursor().sync();

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
			allowPrimaryInbound(source);
			source.launch();
			target.launch();
			target.getCursor().path(rejected).merge(CVMLong.create(7));
			target.getCursor().sync();
			source.getCursor().path(rejected).merge(Sets.of(CVMLong.ONE));
			source.getCursor().sync();

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
			allowPrimaryInbound(source);
			source.launch();
			target.launch();
			source.getCursor().path(outer,inner).merge(CVMLong.create(42));
			source.getCursor().sync();

			try (ConvexRemote peer=ConvexRemote.connect(source.getHostAddress())) {
				assertEquals(CVMLong.create(42),
						target.pullPath(peer,outer,inner).get(5,TimeUnit.SECONDS));
			}
		}
	}

	@Test
	public void testLatticeQueryRejectsNonVectorPath() throws Exception {
		maxNodeServer=new NodeServer<>(MaxLattice.create(),store);
		allowPrimaryInbound(maxNodeServer);
		maxNodeServer.launch();

		try (ConvexRemote peer=ConvexRemote.connect(maxNodeServer.getHostAddress())) {
			AVector<?> payload=Vectors.create(
					MessageTag.LATTICE_QUERY,null,Keyword.create("not-a-vector"));
			Result result=peer.request(Message.create(MessageType.LATTICE_QUERY,payload))
					.get(5,TimeUnit.SECONDS);
			assertEquals(ErrorCodes.ARGUMENT,result.getErrorCode());
		}
	}

	/** A correlated lattice update must fail promptly when its merge is rejected. */
	@Test
	public void testRejectedLatticeValueReturnsError() throws Exception {
		maxNodeServer = new NodeServer<>(MaxLattice.create(), store);
		allowPrimaryInbound(maxNodeServer);
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
		allowPrimaryInbound(maxNodeServer);
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
			allowPrimaryInbound(node);
			node.launch();
			int launchWrites = testStore.rootWrites.get();
			LatticePropagator propagator = node.getPropagator();
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
			node.launch();
			ACell durableBefore = testStore.getRootData();
			assertNotNull(durableBefore, "launch should establish the initial durable snapshot");
			testStore.failRootWrites = true;

			node.handleIncomingMessage(latticeValue(CVMLong.create(42), null));

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
			node.launch();

			LatticePropagator propagator = node.getPropagator();

			// Local write path: set value directly on cursor, then sync explicitly.
			// This is how application code drives the node — sync() is the caller's
			// responsibility (unlike the incoming message path which syncs internally).
			// Synchronous commit: sync() returns after the primary's announce.
			node.getCursor().set(CVMLong.create(42));
			node.getCursor().sync();

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
		maxNodeServer.launch();

		InetSocketAddress addr = maxNodeServer.getHostAddress();
		ConvexRemote peer = ConvexRemote.connect(addr);

		LatticeConnectionManager cm = maxNodeServer.getPropagator().getConnectionManager();
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
		maxNodeServer.setMergeContext(LatticeContext.create(null, serverKey));
		maxNodeServer.launch();

		LatticeConnectionManager cm = maxNodeServer.getPropagator().getConnectionManager();
		ConvexRemote verified = ConvexRemote.connect(maxNodeServer.getHostAddress(), 4096);
		ConvexRemote unverified = ConvexRemote.connect(maxNodeServer.getHostAddress());
		AccountKey wrongKey = AKeyPair.generate().getAccountKey();
		try {
			assertEquals(4096, verified.getMaxInboundMessageLength());
			verified.setKeyPair(clientKey);
			assertEquals(serverKey.getAccountKey(),
				verified.verifyPeer(serverKey.getAccountKey()).get(5, TimeUnit.SECONDS));

			cm.addPeer(serverKey.getAccountKey(), verified);
			assertEquals(8 * 1024 * 1024, verified.getMaxInboundMessageLength(),
				"a connection verified for its manager slot should receive the trusted tier");

			cm.addPeer(wrongKey, unverified);
			assertEquals(4096, unverified.getMaxInboundMessageLength(),
				"an unverified connection must be reduced to the public tier immediately");
		} finally {
			cm.removePeer(serverKey.getAccountKey());
			cm.removePeer(wrongKey);
			verified.close();
			unverified.close();
		}
	}

	/**
	 * Test that addPeer(AccountKey) adds a desired peer with no connection,
	 * and addPeer(AccountKey, InetSocketAddress) creates a desired peer with transport.
	 */
	@Test
	public void testDesiredPeerWithoutConnection() throws Exception {
		ALattice<AInteger> lattice = MaxLattice.create();
		maxNodeServer = new NodeServer<>(lattice, store, NodeConfig.port(-1));
		maxNodeServer.launch();

		LatticeConnectionManager cm = maxNodeServer.getPropagator().getConnectionManager();
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

	/**
	 * Test updateDesiredPeers from NodeInfo-shaped data (simulating P2P lattice discovery).
	 */
	@Test
	public void testUpdateDesiredPeersFromNodeInfo() throws Exception {
		ALattice<AInteger> lattice = MaxLattice.create();
		maxNodeServer = new NodeServer<>(lattice, store, NodeConfig.port(-1));
		maxNodeServer.launch();

		LatticeConnectionManager cm = maxNodeServer.getPropagator().getConnectionManager();

		// Create a signed NodeInfo entry (simulating what P2PLattice produces)
		AKeyPair peerKP = AKeyPair.generate();
		AccountKey peerKey = peerKP.getAccountKey();
		AccountKey ownKey = AKeyPair.generate().getAccountKey();

		AHashMap<Keyword, ACell> nodeInfo = Maps.of(
			Keywords.TRANSPORTS, Vectors.of(Strings.create("tcp://peer.example.com:18888")),
			Keywords.TYPE, Strings.create("Convex Lattice Node"),
			Keywords.VERSION, Strings.create("0.8.3"),
			Keywords.TIMESTAMP, CVMLong.create(System.currentTimeMillis())
		);

		SignedData<ACell> signedInfo = peerKP.signData((ACell) nodeInfo);

		@SuppressWarnings("unchecked")
		AHashMap<ACell, SignedData<ACell>> nodesMap =
			(AHashMap<ACell, SignedData<ACell>>) (AHashMap<?,?>) Maps.of(peerKey, signedInfo);

		// Update desired peers
		cm.updateDesiredPeers(nodesMap, ownKey);

		// Verify
		assertTrue(cm.getDesiredPeers().containsKey(peerKey),
			"Peer from lattice should be in desired peers");
		LatticeConnectionManager.DesiredPeer dp = cm.getDesiredPeers().get(peerKey);
		assertNotNull(dp.transports, "Should have transports from NodeInfo");
		assertEquals(Strings.create("Convex Lattice Node"), dp.type);
		assertEquals(Strings.create("0.8.3"), dp.version);

		// Own key should be skipped
		assertFalse(cm.getDesiredPeers().containsKey(ownKey),
			"Own key should not be in desired peers");
	}

	/**
	 * Test that a dead connection is detected and pruned, and the desired
	 * peer entry survives for reconnection.
	 */
	@Test
	public void testDeadConnectionPruning() throws Exception {
		ALattice<AInteger> lattice = MaxLattice.create();
		maxNodeServer = new NodeServer<>(lattice, store);
		maxNodeServer.launch();

		InetSocketAddress addr = maxNodeServer.getHostAddress();
		ConvexRemote peer = ConvexRemote.connect(addr);

		LatticeConnectionManager cm = maxNodeServer.getPropagator().getConnectionManager();
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
	 * Test that verifyPeer succeeds when the NodeServer has a signing key
	 * and the challenge is addressed to the correct key.
	 */
	@Test
	public void testChallengeResponse() throws Exception {
		AKeyPair serverKP = AKeyPair.generate();
		AKeyPair clientKP = AKeyPair.generate();

		ALattice<AInteger> lattice = MaxLattice.create();
		maxNodeServer = new NodeServer<>(lattice, store);
		maxNodeServer.setMergeContext(LatticeContext.create(null, serverKP));
		maxNodeServer.launch();

		InetSocketAddress addr = maxNodeServer.getHostAddress();
		ConvexRemote convex = Convex.connect(addr, null, clientKP);

		try {
			AccountKey result = convex.verifyPeer(
				serverKP.getAccountKey()).get(5, TimeUnit.SECONDS);
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
		maxNodeServer.setMergeContext(LatticeContext.create(null, serverKP));
		maxNodeServer.launch();

		InetSocketAddress addr = maxNodeServer.getHostAddress();
		ConvexRemote convex = Convex.connect(addr, null, clientKP);

		try {
			// null expectedKey — accept whoever responds
			AccountKey result = convex.verifyPeer(null).get(5, TimeUnit.SECONDS);
			assertEquals(serverKP.getAccountKey(), result, "Should discover server key");
		} finally {
			convex.close();
		}
	}

	/**
	 * Test that verifyPeer fails when the expected key does not match
	 * the NodeServer's actual key.
	 */
	@Test
	public void testChallengeResponseWrongKey() throws Exception {
		AKeyPair serverKP = AKeyPair.generate();
		AKeyPair clientKP = AKeyPair.generate();
		AKeyPair wrongKP = AKeyPair.generate();

		ALattice<AInteger> lattice = MaxLattice.create();
		maxNodeServer = new NodeServer<>(lattice, store);
		maxNodeServer.setMergeContext(LatticeContext.create(null, serverKP));
		maxNodeServer.launch();

		InetSocketAddress addr = maxNodeServer.getHostAddress();
		ConvexRemote convex = Convex.connect(addr, null, clientKP);

		try {
			AccountKey result = convex.verifyPeer(
				wrongKP.getAccountKey()).get(5, TimeUnit.SECONDS);
			assertNull(result, "verifyPeer should fail for wrong server key");
			assertNull(convex.getVerifiedPeer());
		} finally {
			convex.close();
		}
	}

	/**
	 * Test that verifyPeer fails when the NodeServer has no signing key.
	 */
	@Test
	public void testChallengeResponseNoKey() throws Exception {
		AKeyPair clientKP = AKeyPair.generate();

		ALattice<AInteger> lattice = MaxLattice.create();
		maxNodeServer = new NodeServer<>(lattice, store);
		// No setMergeContext — default EMPTY context, no signing key
		maxNodeServer.launch();

		InetSocketAddress addr = maxNodeServer.getHostAddress();
		ConvexRemote convex = Convex.connect(addr, null, clientKP);

		try {
			AccountKey result = convex.verifyPeer(
				AKeyPair.generate().getAccountKey()).get(5, TimeUnit.SECONDS);
			assertNull(result, "verifyPeer should fail when server has no signing key");
		} finally {
			convex.close();
		}
	}

	/**
	 * Test that verifyPeer works with an optional contextID.
	 */
	@Test
	public void testChallengeResponseWithContext() throws Exception {
		AKeyPair serverKP = AKeyPair.generate();
		AKeyPair clientKP = AKeyPair.generate();
		ACell contextID = Strings.create("test-lattice-v1");

		ALattice<AInteger> lattice = MaxLattice.create();
		maxNodeServer = new NodeServer<>(lattice, store);
		maxNodeServer.setMergeContext(LatticeContext.create(null, serverKP));
		maxNodeServer.launch();

		InetSocketAddress addr = maxNodeServer.getHostAddress();
		ConvexRemote convex = Convex.connect(addr, null, clientKP);

		try {
			AccountKey result = convex.verifyPeer(
				serverKP.getAccountKey(), contextID).get(5, TimeUnit.SECONDS);
			assertEquals(serverKP.getAccountKey(), result, "Should succeed with contextID");
		} finally {
			convex.close();
		}
	}
}

