package convex.node;

import java.io.Closeable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.api.Acquiror;
import convex.api.Convex;
import convex.core.ErrorCodes;
import convex.core.Result;
import convex.core.cpos.CPoSConstants;
import convex.core.crypto.AKeyPair;
import convex.core.data.ACell;
import convex.core.data.AVector;
import convex.core.data.Cells;
import convex.core.data.Format;
import convex.core.data.Hash;
import convex.core.data.Ref;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;
import convex.core.exceptions.BadFormatException;
import convex.core.exceptions.MissingDataException;
import convex.core.message.AConnection;
import convex.core.message.BoundedMessageQueue;
import convex.core.message.Message;
import convex.core.message.MessageTag;
import convex.core.message.MessageType;
import convex.core.store.AStore;
import convex.core.util.Utils;
import convex.lattice.cursor.Root;
import convex.peer.Config;

/**
 * Connection-facing CAD036 endpoint owned by one {@link LatticePropagator}.
 *
 * <p>The endpoint is the isolation boundary for a propagation policy group. It
 * owns that group's bounded inbound queue, connection statistics, missing-cell
 * acquisition, challenge correlation and application extension messages. It
 * decodes and projects complete values in the propagator's serving store, then
 * hands only a path and complete value to {@link NodeServer} for authoritative
 * lattice merge and persistence.</p>
 *
 * <p>Neither a malformed message, a failed acquisition nor a broken application
 * handler can terminate the authoritative node publication path. Conversely,
 * this endpoint never mutates the node cursor or its durable root directly.</p>
 */
final class LatticeProtocolEndpoint implements Closeable {

	private static final Logger log=LoggerFactory.getLogger(LatticeProtocolEndpoint.class);
	private static final long CONNECTION_SWEEP_INTERVAL=30_000L;

	private final LatticePropagator propagator;
	private final NodeServer<?> node;
	private final LatticePropagatorConfig config;
	private final AStore store;
	private final BoundedMessageQueue inboundQueue;
	private final Semaphore acquisitionPermits;
	private final Predicate<Message> inboundRetry=this::offerInboundBlocking;
	private final Predicate<Message> inboundRejected=message -> false;

	/** Physically inbound connections assigned to this propagation policy group. */
	private final Set<AConnection> inboundConnections=ConcurrentHashMap.newKeySet();
	private final ConcurrentHashMap<AConnection,ConnectionStats> connectionStats=
		new ConcurrentHashMap<>();
	private final ConcurrentHashMap<AConnection,ConcurrentHashMap<ACell,CompletableFuture<Result>>>
		pendingDataRequests=new ConcurrentHashMap<>();
	private final ConcurrentHashMap<AConnection,Set<Acquiror>> activeAcquirors=
		new ConcurrentHashMap<>();
	private final ConcurrentHashMap<Message,LatticePropagator> acquiredMessages=
		new ConcurrentHashMap<>();
	private final ConcurrentHashMap<Message,Throwable> acquisitionFailures=
		new ConcurrentHashMap<>();
	private final Object acquisitionLifecycleLock=new Object();
	private final LatticeInboundVerifier inboundVerifier;

	private volatile boolean acceptingInbound;
	private volatile boolean inboundRunning;
	private boolean acceptingAcquisitions;
	private Thread inboundThread;
	private Thread maintenanceThread;

	private AKeyPair transportKeyPair;
	private LatticeIngressFilter ingressFilter=(path,value) -> value;
	private Predicate<Message> applicationMessageHandler;
	private InboundLatticeListener inboundLatticeListener;

	LatticeProtocolEndpoint(LatticePropagator propagator,NodeServer<?> node,
			LatticePropagatorConfig config) {
		this.propagator=propagator;
		this.node=node;
		this.config=config;
		this.store=propagator.getStore();
		this.inboundQueue=new BoundedMessageQueue(
			config.getInboundQueueSize(),config.getMaxInboundQueueBytes());
		this.acquisitionPermits=new Semaphore(config.getInboundQueueSize());
		this.inboundVerifier=new LatticeInboundVerifier(() -> transportKeyPair);
	}

	/** Configures the identity used only for live transport possession proofs. */
	void setTransportKeyPair(AKeyPair keyPair) {
		this.transportKeyPair=keyPair;
	}

	/** Configures complete-value admission for this propagation group. */
	void setIngressFilter(LatticeIngressFilter filter) {
		if (filter==null) throw new IllegalArgumentException("Ingress filter must not be null");
		this.ingressFilter=filter;
	}

	/** Configures the handler for complete application extension messages. */
	void setApplicationMessageHandler(Predicate<Message> handler) {
		this.applicationMessageHandler=handler;
	}

	/** Configures the post-merge observer for values received through this group. */
	void setInboundLatticeListener(InboundLatticeListener listener) {
		this.inboundLatticeListener=listener;
	}

	/** Starts this group's ordered ingress and maintenance workers. */
	synchronized void start() {
		if (inboundRunning) return;
		if (inboundThread!=null) throw new IllegalStateException("Previous ingress worker is still active");
		inboundRunning=true;
		acceptingInbound=true;
		synchronized (acquisitionLifecycleLock) {
			acceptingAcquisitions=true;
		}
		inboundThread=Thread.ofPlatform().daemon().name("Lattice propagator ingress").start(this::inboundLoop);
		maintenanceThread=Thread.ofVirtual().name("Lattice propagator connection maintenance")
			.start(this::maintenanceLoop);
	}

	/** Assigns a physical inbound connection permanently to this endpoint. */
	void attachInbound(AConnection connection) {
		if (connection!=null) inboundConnections.add(connection);
	}

	/** Returns whether this policy group owns the physical inbound connection. */
	boolean ownsInbound(AConnection connection) {
		return connection!=null && inboundConnections.contains(connection);
	}

	/**
	 * Non-blocking network delivery entry point. A full queue asks the transport
	 * to pause reads and retry through the returned predicate.
	 */
	Predicate<Message> deliver(Message message) {
		if (!acceptingInbound) return inboundRejected;
		if (inboundQueue.offer(message)) return null;
		return inboundRetry;
	}

	/**
	 * Accepts reverse traffic from a manager-owned authenticated outbound route.
	 * This bypasses physical inbound assignment but not the endpoint's queue or
	 * protocol validation.
	 */
	void receiveFromManagedOutbound(Convex peer,Message message) {
		AccountKeyBinding binding=AccountKeyBinding.from(peer,message);
		if (binding==null || propagator.getConnectionManager().getConnection(binding.key())!=peer) {
			log.debug("Dropped reverse message without current outbound admission");
			return;
		}
		if (deliver(message)!=null) {
			log.debug("Dropped reverse lattice message because this policy queue is full or stopping");
		}
	}

	/** Starts possession verification for an inbound route owned by this group. */
	void authenticateInbound(AConnection connection,ACell expectedKeyCell) {
		var expectedKey=convex.core.lang.RT.ensureAccountKey(expectedKeyCell);
		if (connection==null || expectedKey==null) {
			throw new IllegalArgumentException("Connection and expected key must not be null");
		}
		if (!ownsInbound(connection)) {
			throw new IllegalStateException("Inbound connection belongs to another propagation group");
		}
		inboundVerifier.maybeStart(connection,propagator,expectedKey);
	}

	/** Releases every resource scoped to one physical connection. */
	void removeConnection(AConnection connection) {
		if (connection==null) return;
		inboundConnections.remove(connection);
		inboundVerifier.forget(connection);
		connectionStats.remove(connection);
		propagator.getConnectionManager().removeUpgradedInboundConnection(connection);
		ConcurrentHashMap<ACell,CompletableFuture<Result>> pending=pendingDataRequests.remove(connection);
		if (pending!=null) {
			IOException closed=new IOException("Lattice source connection closed during acquisition");
			pending.values().forEach(future -> future.completeExceptionally(closed));
		}
		Set<Acquiror> acquisitions=activeAcquirors.get(connection);
		if (acquisitions!=null) acquisitions.forEach(Acquiror::close);
		acquiredMessages.keySet().removeIf(message -> message.getConnection()==connection);
		acquisitionFailures.keySet().removeIf(message -> message.getConnection()==connection);
	}

	/** Handles a message synchronously; primarily useful for deterministic tests. */
	void handle(Message message) {
		ConnectionContext context=prepare(message);
		if (context==null) return;
		try {
			dispatch(message,context);
		} catch (Exception e) {
			returnHandlerFailure(message,e);
		}
	}

	private record ConnectionContext(AConnection connection,ConnectionStats stats,boolean acquired) {}

	private ConnectionContext prepare(Message message) {
		AConnection connection=message.getConnection();
		ConnectionStats stats=statsFor(connection);
		Throwable acquisitionFailure=acquisitionFailures.remove(message);
		boolean acquired=acquiredMessages.remove(message)!=null;
		if (!acquired && acquisitionFailure==null) recordReceived(stats);
		if (acquisitionFailure!=null) {
			recordMergeReject(connection,stats);
			returnLatticeResult(message,Result.fromException(acquisitionFailure));
			return null;
		}
		if (!decodeOrAcquire(message,connection,acquired,stats)) return null;
		return new ConnectionContext(connection,stats,acquired);
	}

	private boolean decodeOrAcquire(Message message,AConnection connection,
			boolean acquired,ConnectionStats stats) {
		try {
			AStore decodeStore=(connection==null || connection.isTrusted()) ? store : null;
			message.getPayload(decodeStore);
			return true;
		} catch (MissingDataException e) {
			if (!acquired && connection!=null && connection.isTrusted()) {
				beginLatticeAcquisition(message,stats);
				return false;
			}
			recordDecodeError(connection,stats);
			returnLatticeResult(message,Result.error(ErrorCodes.FORMAT,
				"Only complete lattice values are accepted from unverified connections"));
			return false;
		} catch (Exception e) {
			recordDecodeError(connection,stats);
			try {
				message.returnMessage(Message.createResult(Result.fromException(e).withID(message.getRequestID())));
			} catch (Exception ignored) {
				// Best effort: the connection may already be unusable.
			}
			return false;
		}
	}

	private void dispatch(Message message,ConnectionContext context)
			throws BadFormatException,IOException {
		MessageType type=message.getType();
		if (type==MessageType.RESULT) {
			if (inboundVerifier.handleResult(message)) return;
			if (completeDataRequest(message,context.connection())) return;
		}
		switch (type) {
			case PING -> processPing(message);
			case LATTICE_QUERY -> processLatticeQuery(message);
			case LATTICE_VALUE -> {
				if (context.acquired()) processLatticeValue(message,context.stats());
				else prepareLatticeValue(message,context.stats());
			}
			case DATA -> {
				if (context.connection()!=null && !context.connection().isTrusted()) {
					recordMergeReject(context.connection(),context.stats());
				} else {
					processData(message,context.stats());
				}
			}
			case DATA_REQUEST -> processDataRequest(message);
			case CHALLENGE -> processChallenge(message);
			case UNKNOWN -> processApplicationMessage(message,context);
			default -> log.debug("Unhandled propagation message type: {}",type);
		}
	}

	private void processPing(Message message) {
		ACell id=message.getRequestID();
		if (id!=null) message.returnResult(Result.create(id,CVMLong.create(Utils.getCurrentTimestamp())));
	}

	private void processLatticeQuery(Message message) throws BadFormatException {
		AVector<?> payload=convex.core.lang.RT.ensureVector(message.getPayload());
		if (payload==null || payload.count()!=3 || !MessageTag.LATTICE_QUERY.equals(payload.get(0))) {
			message.returnResult(Result.create(message.getRequestID(),
				Strings.create("Invalid LATTICE_QUERY format"),ErrorCodes.ARGUMENT));
			return;
		}
		ACell id=payload.get(1);
		AVector<?> path=convex.core.lang.RT.ensureVector(payload.get(2));
		if (path==null) {
			message.returnResult(Result.create(id,Strings.create("LATTICE_QUERY path must be a vector"),
				ErrorCodes.ARGUMENT));
			return;
		}
		Root<ACell> announced=propagator.getAnnouncedCursor();
		ACell value=(path.count()==0) ? announced.get()
			: convex.core.lang.RT.getIn(announced.get(),path.toCellArray());
		returnLatticeValue(message,Result.create(id,value));
	}

	/**
	 * Returns a lattice query result within the work this node is prepared to do for
	 * the requesting connection, deciding from cached memory sizes rather than by
	 * attempting an encoding, so a query can never make this node encode more than
	 * it will send.
	 *
	 * <p>An untrusted connection receives the whole value only when it fits one
	 * message of the size this node would accept from it; otherwise it receives the
	 * root alone and must acquire the rest itself, one bounded DATA_REQUEST at a
	 * time. A trusted connection additionally receives a bounded delta of DATA
	 * chunks ahead of the root, as a broadcast would. The root-only reply carries the
	 * result and the value's top cell, which the receiver completes through its
	 * acquire fallback.</p>
	 */
	private void returnLatticeValue(Message message, Result result) {
		AConnection connection=message.getConnection();
		boolean trusted=(connection!=null)&&connection.isTrusted();
		int singleLimit=trusted ? propagator.getMaxDeltaMessageSize() : config.getMaxMessageSize();

		// Upper bound on a complete reply: every non-embedded cell with its storage
		// overhead, plus the top-level encoding. Cached, so O(1) for announced values.
		long bound=result.getMemorySize()+result.getEncodingLength();
		if (bound<=singleLimit) {
			message.returnMessage(Message.create(MessageType.RESULT,result,
				Format.encodeMultiCell(result,true,singleLimit)));
			return;
		}

		if (trusted) {
			long budget=propagator.getMaxDeltaBroadcastSize();
			ArrayList<ACell> cells=(bound<=budget) ? collectBranches(result.getValue(),budget) : null;
			if (cells!=null && !cells.isEmpty()) {
				try {
					List<Message> chunks=Message.createDataMessages(cells,singleLimit,budget);
					for (Message chunk : chunks) {
						// A chunk that cannot be queued is simply acquired by the receiver later
						if (!message.returnMessage(chunk)) break;
					}
				} catch (IllegalArgumentException e) {
					// A single cell exceeds the chunk limit: the receiver acquires instead
				}
			}
		}

		// Root only: the result and the value's top cell, always small
		message.returnMessage(Message.create(MessageType.RESULT,result,
			Format.encodeMultiCell(result,false,0)));
	}

	/**
	 * Collects every non-embedded cell reachable from a value, or returns null if
	 * their encodings exceed the byte budget.
	 */
	private static ArrayList<ACell> collectBranches(ACell value, long byteBudget) {
		ArrayList<ACell> cells=new ArrayList<>();
		ArrayList<Ref<?>> stack=new ArrayList<>();
		HashSet<Ref<?>> seen=new HashSet<>();
		Cells.visitBranchRefs(value,stack::add);
		long total=0;
		while (!stack.isEmpty()) {
			Ref<?> ref=stack.remove(stack.size()-1);
			if (!seen.add(ref)) continue;
			ACell cell=ref.getValue();
			total+=cell.getEncodingLength();
			if (total>byteBudget) return null;
			cells.add(cell);
			Cells.visitBranchRefs(cell,stack::add);
		}
		return cells;
	}

	private void processData(Message message,ConnectionStats stats)
			throws BadFormatException,IOException {
		AConnection connection=message.getConnection();
		AVector<?> payload=convex.core.lang.RT.ensureVector(message.getPayload());
		// Bounded by the inbound message length only: novelty has no cell-count limit
		if (payload==null || payload.count()<2 || !MessageTag.DATA.equals(payload.get(0))) {
			recordMergeReject(connection,stats);
			throw new BadFormatException("Invalid DATA message format");
		}
		for (long i=1;i<payload.count();i++) {
			ACell cell=payload.get(i);
			if (cell==null || cell.isEmbedded()) {
				recordMergeReject(connection,stats);
				throw new BadFormatException("DATA message contains invalid cell");
			}
			Cells.store(cell,store);
		}
		recordNonMergeAccept(stats);
	}

	private void processDataRequest(Message message) throws BadFormatException {
		Message response=message.makeDataResponse(store);
		if (!message.returnMessage(response)) log.debug("Unable to return lattice data: send buffer full");
	}

	private void processChallenge(Message message) {
		message.respondToChallenge(transportKeyPair,Message.LATTICE_PEER_CHALLENGE_CONTEXT::equals);
	}

	private void processApplicationMessage(Message message,ConnectionContext context) {
		Predicate<Message> handler=applicationMessageHandler;
		try {
			if (handler!=null && handler.test(message)) recordNonMergeAccept(context.stats());
			else recordMergeReject(context.connection(),context.stats());
		} catch (RuntimeException | StackOverflowError e) {
			recordMergeReject(context.connection(),context.stats());
			propagator.recordFailure("application message handler",e);
			log.warn("Propagation-group application handler failed",e);
		}
	}

	private void prepareLatticeValue(Message message,ConnectionStats stats) {
		try {
			Message complete=completeLatticeMessage(message);
			processLatticeValue(complete,stats);
		} catch (MissingDataException e) {
			AConnection connection=message.getConnection();
			if (connection!=null && connection.isTrusted()) beginLatticeAcquisition(message,stats);
			else {
				recordDecodeError(connection,stats);
				returnLatticeResult(message,Result.error(ErrorCodes.FORMAT,
					"Only complete lattice values are accepted from unverified connections"));
			}
		} catch (BadFormatException e) {
			recordMergeReject(message.getConnection(),stats);
			returnLatticeResult(message,Result.error(ErrorCodes.FORMAT,e.getMessage()));
		} catch (IOException e) {
			recordMergeReject(message.getConnection(),stats);
			returnLatticeResult(message,Result.fromException(e));
		}
	}

	private Message completeLatticeMessage(Message message) throws BadFormatException,IOException {
		LatticeValuePayload payload=parseLatticeValuePayload(message);
		ACell value=payload.value();
		HashSet<Hash> missing=new HashSet<>();
		Ref.get(value).findMissing(missing,1);
		if (!missing.isEmpty()) throw new MissingDataException(store,missing.iterator().next());
		if (!withinInboundSizeLimit(value)) throw new BadFormatException("Acquired lattice value exceeds inbound size limit");
		ACell[] path=payload.path().toCellArray();
		ACell admitted=ingressFilter.filter(path,value);
		if (admitted==null) throw new BadFormatException("Inbound lattice value is not locally desired");
		if (!withinInboundSizeLimit(admitted)) throw new BadFormatException("Projected lattice value exceeds inbound size limit");
		ACell complete=Cells.persist(admitted,store);
		AVector<?> completePayload=Vectors.create(
			MessageTag.LATTICE_VALUE,payload.id(),payload.path(),complete);
		return Message.create(MessageType.LATTICE_VALUE,completePayload)
			.withConnection(message.getConnection());
	}

	private void processLatticeValue(Message message,ConnectionStats stats) throws BadFormatException {
		LatticeValuePayload payload=parseLatticeValuePayload(message);
		ACell value=payload.value();
		if (!withinInboundSizeLimit(value)) {
			recordMergeReject(message.getConnection(),stats);
			returnLatticeResult(message,Result.error(ErrorCodes.MEMORY,
				"LATTICE_VALUE exceeds the inbound size limit"));
			return;
		}

		ACell[] path=payload.path().toCellArray();
		NodeServer.MergeOutcome outcome=node.mergeInbound(path,value);
		if (!outcome.accepted()) {
			recordMergeReject(message.getConnection(),stats);
			returnLatticeResult(message,Result.error(ErrorCodes.ARGUMENT,"Lattice merge rejected"));
			return;
		}
		recordAccept(stats);
		notifyAccepted(message.getConnection(),path,value,outcome.changed());
		ACell id=message.getRequestID();
		if (id!=null && message.getConnection()!=null
				&& !message.returnMessage(Message.createResult(id,null,null))) {
			log.debug("Unable to return lattice result: send buffer full");
		}
	}

	private void notifyAccepted(AConnection connection,ACell[] path,ACell value,boolean changed) {
		InboundLatticeListener listener=inboundLatticeListener;
		if (listener==null) return;
		try {
			listener.onAccepted(connection,propagator,path,value,changed);
		} catch (RuntimeException | StackOverflowError e) {
			log.warn("Propagation-group inbound listener failed after an accepted merge",e);
		}
	}

	private record LatticeValuePayload(ACell id,AVector<?> path,ACell value) {}

	private static LatticeValuePayload parseLatticeValuePayload(Message message)
			throws BadFormatException {
		AVector<?> payload=convex.core.lang.RT.ensureVector(message.getPayload());
		if (payload==null || payload.count()==0 || !MessageTag.LATTICE_VALUE.equals(payload.get(0))) {
			throw new BadFormatException("Invalid LATTICE_VALUE message format");
		}
		ACell id;
		ACell pathCell;
		ACell value;
		if (payload.count()==4) {
			id=payload.get(1);
			if (id!=null && convex.core.lang.RT.ensureLong(id)==null) {
				throw new BadFormatException("LATTICE_VALUE ID must be a long or nil");
			}
			pathCell=payload.get(2);
			value=payload.get(3);
		} else if (payload.count()==3) {
			id=null;
			pathCell=payload.get(1);
			value=payload.get(2);
		} else {
			throw new BadFormatException("Invalid LATTICE_VALUE message format");
		}
		AVector<?> path=convex.core.lang.RT.ensureVector(pathCell);
		if (path==null) throw new BadFormatException("LATTICE_VALUE path must be a vector");
		if (value==null) throw new BadFormatException("LATTICE_VALUE message missing value");
		return new LatticeValuePayload(id,path,value);
	}

	private boolean withinInboundSizeLimit(ACell value) {
		return ACell.getMemorySize(value)<=config.getMaxInboundValueSize();
	}

	private void beginLatticeAcquisition(Message message,ConnectionStats stats) {
		AConnection connection=message.getConnection();
		if (connection==null) {
			recordMergeReject(null,stats);
			return;
		}
		if (!acquisitionPermits.tryAcquire()) {
			recordMergeReject(connection,stats);
			returnLatticeResult(message,Result.error(ErrorCodes.LOAD,
				"Lattice acquisition capacity exhausted"));
			return;
		}
		CompletableFuture<Message> acquisition;
		try {
			acquisition=acquireLatticeMessage(message,connection);
		} catch (Throwable t) {
			acquisitionPermits.release();
			throw t;
		}
		acquisition.whenComplete((complete,error) -> {
			try {
				if (error==null) {
					acquiredMessages.put(complete,propagator);
					if (!offerInboundBlocking(complete)) acquiredMessages.remove(complete);
				} else {
					acquisitionFailures.put(message,unwrapCompletion(error));
					if (!offerInboundBlocking(message)) acquisitionFailures.remove(message);
				}
			} finally {
				acquisitionPermits.release();
			}
		});
	}

	private CompletableFuture<Message> acquireLatticeMessage(Message message,AConnection connection) {
		try {
			message.getPayload(store);
		} catch (MissingDataException e) {
			return acquireHash(connection,e.getMissingHash())
				.thenCompose(value -> acquireLatticeMessage(message,connection));
		} catch (Exception e) {
			return CompletableFuture.failedFuture(e);
		}
		try {
			if (message.getType()!=MessageType.LATTICE_VALUE) {
				throw new BadFormatException("Missing data acquisition is only valid for LATTICE_VALUE");
			}
			try {
				return CompletableFuture.completedFuture(completeLatticeMessage(message));
			} catch (MissingDataException e) {
				return acquireHash(connection,e.getMissingHash())
					.thenCompose(value -> acquireLatticeMessage(message,connection));
			}
		} catch (Exception e) {
			return CompletableFuture.failedFuture(e);
		}
	}

	private CompletableFuture<ACell> acquireHash(AConnection connection,Hash hash) {
		Acquiror acquiror=Acquiror.create(hash,store,hashes -> requestMissing(connection,hashes));
		Set<Acquiror> acquisitions;
		synchronized (acquisitionLifecycleLock) {
			if (!acceptingAcquisitions) {
				acquiror.close();
				return CompletableFuture.failedFuture(new IOException("Propagation group is stopping"));
			}
			acquisitions=activeAcquirors.computeIfAbsent(connection,c -> ConcurrentHashMap.newKeySet());
			acquisitions.add(acquiror);
		}
		acquiror.getTerminationFuture().whenComplete((ignored,error) -> {
			synchronized (acquisitionLifecycleLock) {
				acquisitions.remove(acquiror);
				if (acquisitions.isEmpty()) activeAcquirors.remove(connection,acquisitions);
			}
		});
		return acquiror.getFuture();
	}

	private CompletableFuture<Result> requestMissing(AConnection connection,Hash[] hashes) {
		if (connection==null || connection.isClosed()) {
			return CompletableFuture.failedFuture(new IOException("Lattice source connection is closed"));
		}
		ACell id=connection.nextRequestID();
		CompletableFuture<Result> future=new CompletableFuture<>();
		ConcurrentHashMap<ACell,CompletableFuture<Result>> byID=
			pendingDataRequests.computeIfAbsent(connection,c -> new ConcurrentHashMap<>());
		byID.put(id,future);
		future.orTimeout(Config.DEFAULT_INTERNAL_TIMEOUT,TimeUnit.MILLISECONDS);
		future.whenComplete((result,error) -> {
			byID.remove(id);
			if (byID.isEmpty()) pendingDataRequests.remove(connection,byID);
		});
		try {
			if (connection.isClosed() || !connection.sendMessage(Message.createDataRequest(id,hashes))) {
				future.completeExceptionally(new IOException("Unable to send lattice DATA_REQUEST"));
			}
		} catch (Exception e) {
			future.completeExceptionally(e);
		}
		return future;
	}

	private boolean completeDataRequest(Message message,AConnection connection) {
		if (connection==null) return false;
		ConcurrentHashMap<ACell,CompletableFuture<Result>> byID=pendingDataRequests.get(connection);
		if (byID==null) return false;
		try {
			ACell id=message.getResultID();
			if (id==null) return false;
			CompletableFuture<Result> future=byID.remove(id);
			if (byID.isEmpty()) pendingDataRequests.remove(connection,byID);
			if (future==null) return false;
			future.complete(message.toResult());
			return true;
		} catch (Exception e) {
			return false;
		}
	}

	private static Throwable unwrapCompletion(Throwable error) {
		while ((error instanceof java.util.concurrent.CompletionException
				|| error instanceof java.util.concurrent.ExecutionException) && error.getCause()!=null) {
			error=error.getCause();
		}
		return error;
	}

	private void inboundLoop() {
		try {
			while (inboundRunning || !inboundQueue.isEmpty()) {
				try {
					Message message=inboundQueue.poll(1,TimeUnit.SECONDS);
					if (message!=null) dispatchSafely(message);
				} catch (InterruptedException e) {
					if (inboundRunning) continue;
				} catch (Exception e) {
					log.warn("Unexpected propagation ingress failure",e);
				}
			}
		} finally {
			acceptingInbound=false;
			inboundRunning=false;
		}
	}

	private void dispatchSafely(Message message) {
		try {
			handle(message);
		} catch (StackOverflowError e) {
			log.warn("Rejected propagation message after stack overflow");
			closeFaultingConnection(message);
		} catch (VirtualMachineError e) {
			throw e;
		} catch (Error e) {
			log.warn("Contained propagation endpoint Error",e);
			closeFaultingConnection(message);
		}
	}

	private void closeFaultingConnection(Message message) {
		AConnection connection=message.getConnection();
		if (connection==null) return;
		try {
			connection.close();
		} catch (Exception ignored) {
			// Best effort.
		} finally {
			removeConnection(connection);
		}
	}

	private boolean offerInboundBlocking(Message message) {
		if (!acceptingInbound) return false;
		try {
			boolean offered=inboundQueue.offer(message,Config.DEFAULT_INTERNAL_TIMEOUT,TimeUnit.MILLISECONDS);
			if (offered && !acceptingInbound) {
				inboundQueue.remove(message);
				return false;
			}
			return offered;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return false;
		}
	}

	private void maintenanceLoop() {
		while (inboundRunning) {
			try {
				Thread.sleep(CONNECTION_SWEEP_INTERVAL);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				break;
			}
			sweepClosedConnections();
		}
	}

	void sweepClosedConnections() {
		HashSet<AConnection> candidates=new HashSet<>(connectionStats.keySet());
		candidates.addAll(inboundConnections);
		candidates.addAll(pendingDataRequests.keySet());
		candidates.addAll(activeAcquirors.keySet());
		for (AConnection connection:candidates) if (connection.isClosed()) removeConnection(connection);
	}

	private ConnectionStats statsFor(AConnection connection) {
		if (connection==null) return null;
		if (connectionStats.size()>config.getMaxConnections()+64) sweepClosedConnections();
		return connectionStats.computeIfAbsent(connection,key -> new ConnectionStats());
	}

	private void recordReceived(ConnectionStats stats) {
		if (stats!=null) stats.messagesReceived++;
	}

	private void recordDecodeError(AConnection connection,ConnectionStats stats) {
		if (stats==null) return;
		stats.decodeErrors++;
		registerReject(connection,stats);
	}

	private void recordMergeReject(AConnection connection,ConnectionStats stats) {
		if (stats==null) return;
		stats.mergesRejected++;
		registerReject(connection,stats);
	}

	private void registerReject(AConnection connection,ConnectionStats stats) {
		stats.consecutiveRejects++;
		long limit=config.getMaxConsecutiveRejects();
		if (limit>0 && stats.consecutiveRejects>=limit && connection!=null && !connection.isClosed()) {
			try {
				connection.close();
			} catch (Exception ignored) {
				// Best effort.
			}
			removeConnection(connection);
		}
	}

	private void recordAccept(ConnectionStats stats) {
		if (stats==null) return;
		stats.mergesAccepted++;
		stats.consecutiveRejects=0;
	}

	private void recordNonMergeAccept(ConnectionStats stats) {
		if (stats!=null) stats.consecutiveRejects=0;
	}

	InboundStats getInboundStats() {
		long connections=0,received=0,accepted=0,rejected=0,decodeErrors=0;
		for (ConnectionStats stats:connectionStats.values()) {
			connections++;
			received+=stats.messagesReceived;
			accepted+=stats.mergesAccepted;
			rejected+=stats.mergesRejected;
			decodeErrors+=stats.decodeErrors;
		}
		return new InboundStats(connections,received,accepted,rejected,decodeErrors);
	}

	private void returnLatticeResult(Message message,Result result) {
		ACell id=message.getRequestID();
		if (id==null || message.getConnection()==null) return;
		message.returnMessage(Message.createResult(result.withID(id)));
	}

	private void returnHandlerFailure(Message message,Exception failure) {
		log.warn("Error handling propagation message: {}",failure.getMessage());
		try {
			if (message.getRequestID()!=null) message.returnResult(Result.fromException(failure));
		} catch (Exception ignored) {
			// Best effort.
		}
	}

	private void stopAcquisitions() throws IOException {
		ArrayList<Acquiror> acquisitions=new ArrayList<>();
		synchronized (acquisitionLifecycleLock) {
			acceptingAcquisitions=false;
			for (Set<Acquiror> values:activeAcquirors.values()) acquisitions.addAll(values);
		}
		acquisitions.forEach(Acquiror::close);
		long timeout=config.getInboundShutdownTimeout();
		long deadline=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(timeout);
		try {
			for (Acquiror acquiror:acquisitions) {
				long remaining=deadline-System.nanoTime();
				if (remaining<=0 || !acquiror.awaitTermination(remaining,TimeUnit.NANOSECONDS)) {
					throw new IOException("Propagation acquisition shutdown incomplete after "+timeout+" ms");
				}
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IOException("Interrupted while stopping propagation acquisitions",e);
		}
	}

	@Override
	public synchronized void close() throws IOException {
		acceptingInbound=false;
		inboundRunning=false;
		inboundVerifier.close();
		IOException failure=null;
		try {
			stopAcquisitions();
		} catch (IOException e) {
			failure=e;
		}

		// Continue every independent cleanup step even when one acquisition or
		// worker refuses to stop. NodeServer isolates the eventual group failure,
		// but the group should still release everything else that it can.
		inboundQueue.signalAll();
		Thread worker=inboundThread;
		if (worker!=null) {
			worker.interrupt();
			try {
				worker.join(config.getInboundShutdownTimeout());
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				failure=append(failure,new IOException(
					"Interrupted while draining propagation ingress",e));
			}
			if (worker.isAlive()) {
				failure=append(failure,new IOException(
					"Propagation ingress did not drain before timeout"));
			} else {
				inboundThread=null;
			}
		}
		Thread maintenance=maintenanceThread;
		if (maintenance!=null) {
			maintenance.interrupt();
			try {
				maintenance.join(config.getInboundShutdownTimeout());
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				failure=append(failure,new IOException(
					"Interrupted while stopping propagation maintenance",e));
			}
			if (maintenance.isAlive()) {
				failure=append(failure,new IOException(
					"Propagation maintenance did not stop before timeout"));
			} else {
				maintenanceThread=null;
			}
		}
		for (AConnection connection:Set.copyOf(inboundConnections)) removeConnection(connection);
		connectionStats.clear();
		if (failure!=null) throw failure;
	}

	private static IOException append(IOException current,IOException additional) {
		if (current==null) return additional;
		current.addSuppressed(additional);
		return current;
	}

	/** Immutable aggregate of connection-scoped ingress counters for one group. */
	record InboundStats(long connections,long messagesReceived,long mergesAccepted,
		long mergesRejected,long decodeErrors) {}

	private static final class ConnectionStats {
		long messagesReceived;
		long mergesAccepted;
		long mergesRejected;
		long decodeErrors;
		long consecutiveRejects;
	}

	/** Validates that a reverse message is bound to its admitted client identity. */
	private record AccountKeyBinding(convex.core.data.AccountKey key) {
		static AccountKeyBinding from(Convex peer,Message message) {
			var key=peer.getVerifiedPeer();
			AConnection connection=message.getConnection();
			if (key==null || connection==null || !key.equals(connection.getTrustedKey())) return null;
			return new AccountKeyBinding(key);
		}
	}
}
