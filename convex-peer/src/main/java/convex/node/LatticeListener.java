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
 * Thin shared TCP listener that assigns each physical inbound connection to one
 * {@link LatticePropagator}.
 *
 * <p>The listener owns only socket acceptance and the immutable assignment map.
 * Once selected, the propagator owns every capability and resource associated
 * with the connection: queue admission, trust, acquisition, statistics, serving
 * store and eventual route upgrade. The listener never decodes a lattice value,
 * accesses a serving store or calls {@link NodeServer}; it may inspect only a
 * bounded top-level envelope to return a correlated admission denial.</p>
 */
final class LatticeListener implements Closeable {

	private static final Logger log=LoggerFactory.getLogger(LatticeListener.class);

	private final NodeConfig config;
	private final Set<LatticePropagator> allowedPropagators;
	private final ConcurrentHashMap<AConnection,LatticePropagator> assignments=
		new ConcurrentHashMap<>();
	private Function<AConnection,LatticePropagator> selector;
	private AServer server;
	private Integer port;

	LatticeListener(NodeConfig config,Set<LatticePropagator> allowedPropagators) {
		this.config=config;
		this.allowedPropagators=allowedPropagators;
		this.port=config.getPort();
	}

	/** Configures the operator policy used once for each new inbound socket. */
	void setSelector(Function<AConnection,LatticePropagator> selector) {
		if (server!=null) throw new IllegalStateException("Inbound selector must be configured before launch");
		this.selector=selector;
	}

	/** Starts the optional listener; a negative port represents an outbound-only node. */
	void launch() throws IOException,InterruptedException {
		if (port!=null && port<0) return;
		NettyServer netty=new NettyServer(port);
		netty.setMessageDelivery(this::deliver);
		netty.setDisconnectAction(this::removeConnection);
		netty.setMaxClientConnections(config.getMaxConnections());
		netty.setMaxMessageLength(config.getMaxMessageSize());
		server=netty;
		server.launch();
		port=server.getPort();
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
				log.warn("Inbound policy selected a propagator not attached to this node");
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
	void removeConnection(AConnection connection) {
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

	Integer getPort() {
		return port;
	}

	InetSocketAddress getHostAddress() {
		return server==null ? null : server.getHostAddress();
	}

	@Override
	public void close() {
		AServer current=server;
		if (current!=null) current.close();
		for (AConnection connection:Set.copyOf(assignments.keySet())) removeConnection(connection);
		server=null;
	}
}
