package convex.node;

import java.io.Closeable;
import java.io.IOException;
import java.net.InetSocketAddress;
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
 * closing those groups.</p>
 */
public final class LatticeListener implements Closeable {

	private static final Logger log=LoggerFactory.getLogger(LatticeListener.class);

	private final NodeConfig config;
	private final Set<LatticePropagator> allowedPropagators=
		ConcurrentHashMap.newKeySet();
	private final ConcurrentHashMap<AConnection,LatticePropagator> assignments=
		new ConcurrentHashMap<>();
	private Function<AConnection,LatticePropagator> selector;
	private AServer server;
	private Integer port;
	private boolean launchStarted;
	private boolean running;

	/**
	 * Creates a TCP lattice listener. No propagation group is admitted until it
	 * is explicitly registered and selected.
	 *
	 * @param config host limits and port, or {@code null} for defaults
	 */
	public LatticeListener(NodeConfig config) {
		this.config=(config==null) ? NodeConfig.create() : config;
		this.port=this.config.getPort();
	}

	/**
	 * Registers a propagation group as an eligible target of this transport.
	 * The listener does not take lifecycle ownership of the group.
	 *
	 * @param propagator group which may receive accepted connections
	 */
	public synchronized void registerPropagator(LatticePropagator propagator) {
		if (propagator==null) throw new IllegalArgumentException("Propagator must not be null");
		requireNotLaunched("registerPropagator");
		allowedPropagators.add(propagator);
	}

	/**
	 * Configures the application policy used once for each new inbound socket.
	 * The returned group must have been registered with this listener.
	 *
	 * @param selector assignment policy, or {@code null} to deny every connection
	 */
	public synchronized void setSelector(Function<AConnection,LatticePropagator> selector) {
		requireNotLaunched("setSelector");
		this.selector=selector;
	}

	/**
	 * Opens the listener. A negative port represents an intentionally absent
	 * inbound transport and completes launch without binding a socket.
	 *
	 * @throws IOException if the transport cannot bind or start
	 * @throws InterruptedException if launch is interrupted
	 */
	public synchronized void launch() throws IOException,InterruptedException {
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
			server.launch();
			port=server.getPort();
			running=true;
		} catch (IOException | InterruptedException | RuntimeException | Error e) {
			server.close();
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
			propagator=select(connection);
			if (propagator==null) return reject(message);
			LatticePropagator previous=assignments.putIfAbsent(connection,propagator);
			if (previous!=null) propagator=previous;
			else {
				try {
					propagator.attachInboundConnection(connection);
				} catch (VirtualMachineError e) {
					if (!(e instanceof StackOverflowError)) throw e;
					return containFailure(connection,propagator,e);
				} catch (Throwable e) {
					return containFailure(connection,propagator,e);
				}
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
		log.warn("Propagation group failed while receiving; closing its connection",failure);
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
		boolean returned=false;
		try {
			// Decode only the bounded top-level protocol envelope so a correlated
			// denial can be returned. No serving store is supplied, so this cannot
			// acquire or traverse partial lattice data.
			message.getPayload(null);
			if (message.getRequestID()!=null) {
				returned=message.returnResult(Result.error(ErrorCodes.TRUST,
					Strings.create("No propagation policy admits this connection")));
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

	private LatticePropagator select(AConnection connection) {
		Function<AConnection,LatticePropagator> policy=selector;
		if (policy==null) return null;
		try {
			LatticePropagator selected=policy.apply(connection);
			if (selected==null) return null;
			if (!allowedPropagators.contains(selected)) {
				log.warn("Inbound policy selected a propagator not registered with this listener");
				return null;
			}
			return selected;
		} catch (VirtualMachineError e) {
			if (!(e instanceof StackOverflowError)) throw e;
			log.warn("Inbound propagation policy overflowed; connection remains unassigned",e);
			return null;
		} catch (Throwable e) {
			log.warn("Inbound propagation policy failed; connection remains unassigned",e);
			return null;
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
			log.warn("Propagation group overflowed during connection cleanup",e);
		} catch (Throwable e) {
			log.warn("Propagation group failed during connection cleanup",e);
		}
	}

	/** Returns the configured port, or the actual bound port after launch. */
	public Integer getPort() {
		return port;
	}

	/** Returns the bound TCP address, or {@code null} when no socket is open. */
	public InetSocketAddress getHostAddress() {
		return server==null ? null : server.getHostAddress();
	}

	/** Returns whether launch completed and the listener has not been closed. */
	public boolean isRunning() {
		return running;
	}

	@Override
	public synchronized void close() {
		AServer current=server;
		if (current!=null) current.close();
		for (AConnection connection:Set.copyOf(assignments.keySet())) removeConnection(connection);
		server=null;
		running=false;
	}
}
