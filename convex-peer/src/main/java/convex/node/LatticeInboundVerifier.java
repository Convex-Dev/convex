package convex.node;

import java.io.IOException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.core.Result;
import convex.core.data.ACell;
import convex.core.data.AccountKey;
import convex.core.data.Blob;
import convex.core.data.Hash;
import convex.core.data.SignedData;
import convex.core.exceptions.BadFormatException;
import convex.core.message.AConnection;
import convex.core.message.ChallengeSigner;
import convex.core.message.Message;
import convex.core.util.ThreadUtils;

/**
 * Authenticates physically inbound lattice connections before explicitly
 * upgrading them into outbound propagation routes.
 *
 * <p>Operator assignment to a {@link LatticePropagator} permits inbound lattice
 * access but does not authenticate the remote endpoint. This verifier preserves
 * that distinction: only a successful live challenge for the key the application
 * vouched for on this connection can call
 * {@link LatticeConnectionManager#upgradeInboundConnection(AConnection)}.</p>
 */
final class LatticeInboundVerifier {

	private static final Logger log = LoggerFactory.getLogger(LatticeInboundVerifier.class.getName());
	private static final long TIMEOUT_MS = 5_000L;

	private record PendingVerification(LatticePropagator owner,
			AccountKey claimedKey,
			CompletableFuture<Message> future) {}

	/** Supplies the propagator group's transport identity at challenge time. */
	private final Supplier<ChallengeSigner> signerSupplier;
	private final SecureRandom random = new SecureRandom();
	private final ConcurrentHashMap<AConnection, PendingVerification> active =
		new ConcurrentHashMap<>();
	/** Threads running attempts, which closing waits for. */
	private final Set<Thread> threads = ConcurrentHashMap.newKeySet();
	private volatile boolean closed;

	LatticeInboundVerifier(Supplier<ChallengeSigner> signerSupplier) {
		this.signerSupplier = signerSupplier;
	}

	/** Starts at most one non-blocking authentication attempt per inbound connection, unless closed. */
	void maybeStart(AConnection connection, LatticePropagator owner, AccountKey claimedKey) {
		if (connection == null || owner == null || connection.isClosed()
				|| connection.isTrusted() || !connection.supportsMessage()
				|| claimedKey == null) return;

		CompletableFuture<Message> resultFuture = new CompletableFuture<>();
		PendingVerification pending = new PendingVerification(owner, claimedKey, resultFuture);
		if (active.putIfAbsent(connection, pending) != null) return;

		Thread thread = null;
		try {
			thread = Thread.ofVirtual().name("Lattice inbound authentication").unstarted(() -> {
				try {
					verify(connection, pending);
				} finally {
					threads.remove(Thread.currentThread());
				}
			});
			threads.add(thread);
			// Both registered before the check, so a close either finds this attempt or stops it here
			if (closed) {
				threads.remove(thread);
				active.remove(connection, pending);
				return;
			}
			thread.start();
		} catch (RuntimeException e) {
			if (thread != null) threads.remove(thread);
			active.remove(connection, pending);
		}
	}

	private void verify(AConnection connection, PendingVerification pending) {
		try {
			// A close that found this thread not yet started did not wait for it
			if (closed) return;
			ChallengeSigner signer = signerSupplier.get();
			if (signer == null) return;

			Hash token = Blob.createRandom(random, 16).getHash();
			AccountKey ownKey = signer.getAccountKey();
			SignedData<ACell> signed = signer.challenge(token, pending.claimedKey());
			Message resultMessage = connection.request(Message.createChallenge(null,signed),0,pending.future())
				.get(TIMEOUT_MS,TimeUnit.MILLISECONDS);
			try {
				resultMessage.getPayload(pending.owner().getStore());
			} catch (BadFormatException e) {
				log.debug("Bad lattice authentication response from {}: {}",
					connection.getRemoteAddress(), e.getMessage());
				return;
			}

			Result result = resultMessage.toResult();
			AccountKey remoteKey = Message.verifyChallengeResponse(
				result, token, ownKey, Message.LATTICE_PEER_CHALLENGE_CONTEXT,
				pending.claimedKey());
			if (remoteKey == null || connection.isClosed()) return;

			// Trust is established only here, for the key the application vouched for:
			// the response above must be signed by that key. upgradeInboundConnection
			// independently refuses a connection without a trusted key.
			connection.setTrustedKey(remoteKey);
			pending.owner().getConnectionManager().upgradeInboundConnection(connection);
		} catch (Exception e) {
			log.debug("Inbound lattice authentication failed for {}: {}",
				connection.getRemoteAddress(), e.getMessage());
		} finally {
			pending.future().cancel(false);
			active.remove(connection, pending);
		}
	}

	/** The keys claimed by the connections being challenged now. */
	Set<AccountKey> claimedKeys() {
		HashSet<AccountKey> keys = new HashSet<>();
		for (PendingVerification pending : active.values()) keys.add(pending.claimedKey());
		return keys;
	}

	/** Cancels verification state when the propagator observes physical disconnect. */
	void forget(AConnection connection) {
		PendingVerification pending = active.remove(connection);
		if (pending != null) {
			pending.future().completeExceptionally(
				new IOException("Inbound connection closed during authentication"));
		}
	}

	/**
	 * Cancels every pending attempt during propagator shutdown, starts no more, and
	 * waits at most the challenge timeout for the attempts to end.
	 */
	void close() {
		closed = true;
		for (AConnection connection : active.keySet()) forget(connection);
		long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(TIMEOUT_MS);
		for (Thread thread : List.copyOf(threads)) {
			Duration left = Duration.ofNanos(Math.max(0, deadline - System.nanoTime()));
			if (!ThreadUtils.joinUninterruptibly(thread, left) && (thread != Thread.currentThread())) {
				log.warn("Lattice inbound authentication still running after {} ms", TIMEOUT_MS);
				return;
			}
		}
	}
}
