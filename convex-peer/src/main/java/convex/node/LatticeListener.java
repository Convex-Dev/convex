package convex.node;

import java.io.Closeable;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Predicate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.core.ErrorCodes;
import convex.core.Result;
import convex.core.data.Strings;
import convex.core.message.AConnection;
import convex.core.message.Message;
import convex.net.AServer;
import convex.net.impl.netty.NettyServer;

/**
 * Application-owned TCP transport that assigns each physical inbound
 * connection to one {@link LatticePropagator}.
 *
 * <p>The listener owns only socket acceptance and the immutable assignment map.
 * Once selected, the propagator owns every capability and resource associated
 * with the connection: queue admission, trust, acquisition, statistics, serving
 * store and eventual route upgrade. The listener never decodes a lattice value,
 * accesses a serving store or calls {@link NodeServer}; it may inspect only a
 * bounded top-level envelope to return a correlated admission denial.</p>
 *
 * <p>A calling application may register several propagators with one listener,
 * or give different propagators independent listeners. Registration grants
 * only eligibility for the application selector; it does not attach, start or
 * close a propagator. The application must start the authoritative node and its
 * attached groups before opening this listener, then close the listener before
 * closing those groups. Closing is final: a closed listener cannot be launched
 * again.</p>
 *
 * <p>The listener takes no lock. Its owner launches and closes it, one after the
 * other. Network threads assign connections while the owner registers and revokes
 * groups; a connection that arrives as its group is revoked is turned away.</p>
 */
public final class LatticeListener implements Closeable {

	private static final Logger log=LoggerFactory.getLogger(LatticeListener.class);
	private static final String UNAVAILABLE_GROUP="Inbound propagation policy selected an unavailable group";

	private final NodeConfig config;
	private final Set<LatticePropagator> allowedPropagators;
	/** Whether the eligible groups are a {@link LatticeNode}'s, which only the node changes. */
	private final boolean nodeGroups;
	private final ConcurrentHashMap<AConnection,LatticePropagator> assignments=
		new ConcurrentHashMap<>();
	private volatile Function<AConnection,LatticePropagator> selector;
	private volatile AServer server;
	private volatile Integer port;
	private boolean launchStarted;
	private boolean closed;
	private volatile boolean running;

	/**
	 * Creates a TCP lattice listener. No propagator group is admitted until it
	 * is explicitly registered and selected.
	 *
	 * @param config host limits and port, or {@code null} for defaults
	 */
	public LatticeListener(NodeConfig config) {
		this(config,ConcurrentHashMap.newKeySet(),false);
	}

	/**
	 * Creates a listener of a node, which may assign connections to the node's groups.
	 * The node adds and removes them in {@code groups}, which all its listeners share,
	 * and calls {@link #revoke(LatticePropagator)} on each listener after removing one.
	 */
	LatticeListener(NodeConfig config,Set<LatticePropagator> groups) {
		this(config,groups,true);
	}

	private LatticeListener(NodeConfig config,Set<LatticePropagator> groups,boolean nodeGroups) {
		this.config=(config==null) ? NodeConfig.create() : config;
		this.port=this.config.getPort();
		this.allowedPropagators=groups;
		this.nodeGroups=nodeGroups;
	}

	/**
	 * Registers a propagator group as an eligible target of this transport.
	 * The listener does not take lifecycle ownership of the group.
	 *
	 * @param propagator group which may receive accepted connections
	 * @throws IllegalStateException on a {@link LatticeNode}'s listener, whose groups
	 *         are the node's
	 */
	public void registerPropagator(LatticePropagator propagator) {
		if (propagator==null) throw new IllegalArgumentException("Propagator must not be null");
		requireOwnGroups();
		allowedPropagators.add(propagator);
	}

	/**
	 * Revokes a group's eligibility and closes its assigned sockets on this listener.
	 * May be called while running. Other groups, listeners and the propagator itself
	 * remain live. Already accepted protocol work may complete in the group.
	 *
	 * @param propagator group to unregister
	 * @return {@code true} if registered, or {@code false} if already absent
	 * @throws IllegalStateException on a {@link LatticeNode}'s listener, whose groups
	 *         are the node's
	 */
	public boolean unregisterPropagator(LatticePropagator propagator) {
		if (propagator==null) throw new IllegalArgumentException("Propagator must not be null");
		requireOwnGroups();
		if (!allowedPropagators.remove(propagator)) return false;
		revoke(propagator);
		return true;
	}

	/**
	 * Closes a group's sockets on this listener once the group has been removed from
	 * the groups this listener serves.
	 */
	void revoke(LatticePropagator propagator) {
		ArrayList<AConnection> connections=new ArrayList<>();
		assignments.forEach((connection,owner) -> {
			if (owner==propagator) connections.add(connection);
		});
		for (AConnection connection:connections) {
			try {
				connection.close();
			} catch (RuntimeException | StackOverflowError e) {
				propagator.recordFailure("listener unregistration",e);
				log.warn("Unable to close unregistered group's inbound connection",e);
			} finally {
				removeConnection(connection);
			}
		}
	}

	private void requireOwnGroups() {
		if (nodeGroups) {
			throw new IllegalStateException(
				"A lattice node's listener serves the node's groups: add or remove them through the node");
		}
	}

	/**
	 * Configures the application policy used once for each new inbound socket.
	 * The returned group must have been registered with this listener.
	 * Changes affect new assignments; established sockets keep their group until
	 * closed or the group is unregistered.
	 *
	 * @param selector assignment policy, or {@code null} to deny every connection
	 */
	public void setSelector(Function<AConnection,LatticePropagator> selector) {
		this.selector=selector;
	}

	/**
	 * Opens the listener, or does nothing if it is already open. A negative port
	 * represents an intentionally absent inbound transport and completes launch
	 * without binding a socket.
	 *
	 * @throws IOException if the transport cannot bind or start
	 * @throws InterruptedException if launch is interrupted
	 * @throws IllegalStateException if the listener is closed, or an earlier launch failed
	 */
	public void launch() throws IOException,InterruptedException {
		if (closed) throw new IllegalStateException("Listener is closed");
		if (running) return;
		requireNotLaunched("launch");
		launchStarted=true;
		if (port!=null && port<0) {
			running=true;
			return;
		}
		NettyServer netty=new NettyServer(port);
		netty.setMessageDelivery(this::deliver);
		netty.setDisconnectAction(this::removeConnection);
		netty.setMaxClientConnections(config.getMaxConnections());
		netty.setMaxMessageLength(config.getMaxMessageSize());
		server=netty;
		try {
			netty.launch();
			port=netty.getPort();
			running=true;
		} catch (IOException | InterruptedException | RuntimeException | Error e) {
			netty.close();
			server=null;
			throw e;
		}
	}

	private void requireNotLaunched(String operation) {
		if (launchStarted) {
			throw new IllegalStateException(operation+" must precede listener launch");
		}
	}

	private Predicate<Message> deliver(Message message) {
		AConnection connection=message.getConnection();
		if (connection==null) return reject(message);
		LatticePropagator propagator=assignments.get(connection);
		if (propagator==null) {
			Selection selection=select(connection);
			propagator=selection.propagator();
			if (propagator==null) return reject(message,selection.rejection());
			try {
				propagator.attachInboundConnection(connection);
			} catch (VirtualMachineError e) {
				if (!(e instanceof StackOverflowError)) throw e;
				return containFailure(connection,propagator,e);
			} catch (Throwable e) {
				// A group removed meanwhile turns connections away as it closes: no failure
				if (!allowedPropagators.contains(propagator)) return reject(message,UNAVAILABLE_GROUP);
				return containFailure(connection,propagator,e);
			}
			// A connection's messages arrive one at a time, so nothing else assigns it.
			// Revoking the group removes it before collecting its sockets, so checking after
			// assigning turns the connection away here, or revocation releases it.
			assignments.put(connection,propagator);
			if (!allowedPropagators.contains(propagator)) {
				removeConnection(connection);
				return reject(message,UNAVAILABLE_GROUP);
			}
		}
		try {
			return propagator.deliverIncomingMessage(message);
		} catch (VirtualMachineError e) {
			if (!(e instanceof StackOverflowError)) throw e;
			return containFailure(connection,propagator,e);
		} catch (Throwable e) {
			return containFailure(connection,propagator,e);
		}
	}

	private Predicate<Message> containFailure(AConnection connection,
			LatticePropagator propagator,Throwable failure) {
		propagator.recordFailure("inbound delivery",failure);
		log.warn("Propagator group failed while receiving; closing its connection",failure);
		assignments.remove(connection,propagator);
		try {
			propagator.removeInboundConnection(connection);
		} catch (VirtualMachineError cleanupFailure) {
			if (!(cleanupFailure instanceof StackOverflowError)) throw cleanupFailure;
			if (cleanupFailure!=failure) failure.addSuppressed(cleanupFailure);
		} catch (Throwable cleanupFailure) {
			if (cleanupFailure!=failure) failure.addSuppressed(cleanupFailure);
		}
		try {
			connection.close();
		} catch (RuntimeException closeFailure) {
			log.debug("Unable to close failed propagation connection",closeFailure);
		}
		return null;
	}

	/** Best-effort correlated denial; optimistic pushes are simply rejected. */
	private Predicate<Message> reject(Message message) {
		return reject(message,"No propagation policy admits this connection");
	}

	private Predicate<Message> reject(Message message,String reason) {
		boolean returned=false;
		try {
			// Decode only the bounded top-level protocol envelope so a correlated
			// denial can be returned. No serving store is supplied, so this cannot
			// acquire or traverse partial lattice data.
			message.getPayload(null);
			if (message.getRequestID()!=null) {
				returned=message.returnResult(Result.error(ErrorCodes.TRUST,
					Strings.create(reason)));
			}
		} catch (Exception ignored) {
			// The frame may be malformed or have no usable return path.
		}
		// Closing gives malformed and optimistic frames a prompt, bounded denial
		// instead of retaining an unassigned socket indefinitely.
		if (!returned) message.closeConnection();
		// Rejection was handled. A non-null predicate means backpressure and would
		// cause the transport to retry the same denied frame.
		return null;
	}

	private record Selection(LatticePropagator propagator,String rejection) {}

	private Selection select(AConnection connection) {
		Function<AConnection,LatticePropagator> policy=selector;
		if (policy==null) return new Selection(null,"No propagation policy is configured");
		try {
			LatticePropagator selected=policy.apply(connection);
			if (selected==null) return new Selection(null,"No propagation policy admits this connection");
			if (!allowedPropagators.contains(selected)) {
				log.warn("Inbound policy selected a propagator not registered with this listener");
				return new Selection(null,UNAVAILABLE_GROUP);
			}
			return new Selection(selected,null);
		} catch (VirtualMachineError e) {
			if (!(e instanceof StackOverflowError)) throw e;
			log.warn("Inbound propagation policy overflowed for {}; connection denied",
				connection.getRemoteAddress());
			log.debug("Inbound propagation policy overflow",e);
			return new Selection(null,"Inbound propagation policy exceeded its execution stack");
		} catch (Throwable e) {
			log.warn("Inbound propagation policy failed for {}; connection denied: {}: {}",
				connection.getRemoteAddress(),e.getClass().getSimpleName(),e.getMessage());
			log.debug("Inbound propagation policy failure",e);
			return new Selection(null,"Inbound propagation policy failed");
		}
	}

	/** Releases the assignment and delegates all connection cleanup to its owner. */
	private void removeConnection(AConnection connection) {
		LatticePropagator propagator=assignments.remove(connection);
		if (propagator==null) return;
		try {
			propagator.removeInboundConnection(connection);
		} catch (VirtualMachineError e) {
			if (!(e instanceof StackOverflowError)) throw e;
			log.warn("Propagator group overflowed during connection cleanup",e);
		} catch (Throwable e) {
			log.warn("Propagator group failed during connection cleanup",e);
		}
	}

	/** Returns the configured port, or the actual bound port after launch. */
	public Integer getPort() {
		return port;
	}

	/** Returns the bound TCP address, or {@code null} when no socket is open. */
	public InetSocketAddress getHostAddress() {
		AServer current=server;
		return current==null ? null : current.getHostAddress();
	}

	/** Returns whether launch completed and the listener has not been closed. */
	public boolean isRunning() {
		return running;
	}

	/** Closes the listener for good; closing again does nothing. */
	@Override
	public void close() {
		closed=true;
		AServer current=server;
		if (current!=null) current.close();
		for (AConnection connection:Set.copyOf(assignments.keySet())) removeConnection(connection);
		server=null;
		running=false;
	}
}
