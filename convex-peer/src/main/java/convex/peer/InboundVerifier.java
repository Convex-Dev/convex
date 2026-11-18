package convex.peer;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.core.cpos.BeliefMerge;
import convex.core.crypto.AKeyPair;
import convex.core.cvm.Peer;
import convex.core.data.ACell;
import convex.core.data.AccountKey;
import convex.core.data.Blob;
import convex.core.data.Hash;
import convex.core.data.SignedData;
import convex.core.message.AConnection;
import convex.core.message.Message;

/**
 * Establishes which connections opened to this peer come from staked peers, so that
 * the cells they push, as DATA or in a Belief, are stored. Owned by {@link Server}.
 *
 * <p>An unverified connection is challenged when it sends a Belief or DATA. It becomes
 * verified ({@link AConnection#setVerifiedKey}) when its far end gives the bound answer
 * ({@link Message#answerBoundChallenge}) naming this peer as the peer it dialled,
 * signed by a key with at least the minimum stake. A failed attempt delays the next
 * one for that connection, doubling with each failure.</p>
 *
 * <p>{@link #maybeStart(AConnection)} runs on network threads: it refuses without
 * allocating, and an attempt it starts runs on a virtual thread of its own.</p>
 */
class InboundVerifier {

	private static final Logger log = LoggerFactory.getLogger(InboundVerifier.class.getName());

	static final long TIMEOUT_MS = 5000;

	/** An attempt in flight: its reply future and a signal that its thread has finished. */
	private record Attempt(CompletableFuture<Message> reply, CompletableFuture<Void> stopped) {}

	/**
	 * A connection's verification state. Replaced, never mutated, under this verifier's
	 * lock; read without it on network threads.
	 *
	 * @param failures attempts failed so far
	 * @param retryAt earliest timestamp of the next attempt
	 * @param attempt the attempt in flight, or null
	 */
	private record Entry(int failures, long retryAt, Attempt attempt) {}

	private final Server server;
	private final SecureRandom random = new SecureRandom();
	private final ConcurrentHashMap<AConnection, Entry> entries = new ConcurrentHashMap<>();
	private volatile boolean closed;

	/** Told the outcome of each attempt once it is recorded. For tests. */
	private volatile BiConsumer<AConnection, Boolean> outcomeObserver;

	InboundVerifier(Server server) {
		this.server = server;
	}

	/**
	 * Starts an attempt to verify a connection, unless it is verified, cannot be sent
	 * messages, has an attempt in flight, or is still backing off after a failure.
	 *
	 * @param conn Connection that sent a Belief or DATA, or null
	 */
	void maybeStart(AConnection conn) {
		if (conn == null || conn.isVerified() || closed) return;
		Entry entry = entries.get(conn);
		if (entry != null && !mayStart(entry, server.getTimestamp())) return;
		if (!conn.supportsMessage() || conn.isClosed()) return;
		start(conn);
	}

	private static boolean mayStart(Entry entry, long now) {
		return entry.attempt() == null && now >= entry.retryAt();
	}

	private void start(AConnection conn) {
		Attempt attempt = new Attempt(new CompletableFuture<>(), new CompletableFuture<>());
		synchronized (this) {
			if (closed) return;
			Entry entry = entries.get(conn);
			// Another network thread may have started one since the unlocked check
			if (entry != null && !mayStart(entry, server.getTimestamp())) return;
			int failures = (entry == null) ? 0 : entry.failures();
			entries.put(conn, new Entry(failures, 0, attempt));
		}
		try {
			Thread.ofVirtual().name("Peer inbound verification").start(() -> verify(conn, attempt));
		} catch (RuntimeException e) {
			finish(conn, attempt, false);
			attempt.stopped().complete(null);
		}
	}

	private void verify(AConnection conn, Attempt attempt) {
		boolean verified = false;
		try {
			removeClosed();
			Peer peer = server.getPeer();
			AKeyPair kp = (peer == null) ? null : peer.getKeyPair();
			if (kp == null) return;

			Hash token = Blob.createRandom(random, 16).getHash();
			AccountKey ownKey = kp.getAccountKey();
			Hash networkID = peer.getNetworkID();
			SignedData<ACell> signed = Message.signChallenge(kp, token, null, networkID);
			Message reply = conn.request(Message.createChallenge(null, signed), 0, attempt.reply())
				.get(TIMEOUT_MS, TimeUnit.MILLISECONDS);

			// An answer is complete in itself: decode it without the store
			reply.getPayload(null);
			AccountKey remoteKey = Message.verifyBoundChallengeResponse(reply.toResult(), token, ownKey, networkID);
			if (remoteKey == null) {
				log.debug("Inbound connection gave no bound answer: {}", conn.getRemoteAddress());
				return;
			}
			if (!BeliefMerge.isValidPeer(server.getState(), remoteKey)) {
				log.debug("Inbound connection key {} is not a staked peer", remoteKey);
				return;
			}
			conn.setVerifiedKey(remoteKey);
			verified = true;
			log.info("Verified inbound peer {} from {}", remoteKey, conn.getRemoteAddress());
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		} catch (Exception e) {
			log.debug("Inbound verification failed for {}: {}", conn.getRemoteAddress(), e.toString());
		} finally {
			try {
				attempt.reply().cancel(false);
				finish(conn, attempt, verified);
				BiConsumer<AConnection, Boolean> observer = outcomeObserver;
				if (observer != null) observer.accept(conn, verified);
			} finally {
				// Always, or close() would wait for this attempt forever
				attempt.stopped().complete(null);
			}
		}
	}

	/** Records an attempt's outcome: forgets a verified or closed connection, else backs it off. */
	private void finish(AConnection conn, Attempt attempt, boolean verified) {
		synchronized (this) {
			Entry entry = entries.get(conn);
			if (entry == null || entry.attempt() != attempt) return;
			if (verified || closed || conn.isClosed()) {
				entries.remove(conn);
			} else {
				int failures = entry.failures() + 1;
				long delay = ConnectionManager.backoff(server.getConnectionManager().getPollDelay(), failures);
				entries.put(conn, new Entry(failures, server.getTimestamp() + delay, null));
			}
		}
	}

	/** Forgets a connection that has closed. An attempt in flight forgets it as it ends. */
	void forget(AConnection conn) {
		synchronized (this) {
			Entry entry = entries.get(conn);
			if (entry != null && entry.attempt() == null) entries.remove(conn);
		}
	}

	/** Forgets closed connections not otherwise reported, as some transports do not. */
	private void removeClosed() {
		synchronized (this) {
			entries.entrySet().removeIf(e -> e.getValue().attempt() == null && e.getKey().isClosed());
		}
	}

	/** Number of connections with recorded state, for tests. */
	int getEntryCount() {
		return entries.size();
	}

	/** Whether an attempt to verify a connection is in flight, for tests. */
	boolean isVerifying(AConnection conn) {
		Entry entry = entries.get(conn);
		return entry != null && entry.attempt() != null;
	}

	/** Sets the observer told each attempt's outcome, for tests. */
	void setOutcomeObserver(BiConsumer<AConnection, Boolean> observer) {
		outcomeObserver = observer;
	}

	/** Cancels and waits for every attempt in flight, and forgets all connections. Idempotent. */
	void close() {
		List<Attempt> attempts = new ArrayList<>();
		synchronized (this) {
			closed = true;
			for (Entry entry : entries.values()) {
				if (entry.attempt() != null) attempts.add(entry.attempt());
			}
		}
		for (Attempt attempt : attempts) {
			attempt.reply().completeExceptionally(new IllegalStateException("Peer server closed"));
		}
		CompletableFuture.allOf(attempts.stream()
			.map(Attempt::stopped)
			.toArray(CompletableFuture[]::new)).join();
		entries.clear();
	}
}
