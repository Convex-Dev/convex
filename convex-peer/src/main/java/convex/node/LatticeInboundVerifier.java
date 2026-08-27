package convex.node;

import java.io.IOException;
import java.security.SecureRandom;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.core.Result;
import convex.core.crypto.AKeyPair;
import convex.core.data.ACell;
import convex.core.data.AccountKey;
import convex.core.data.Blob;
import convex.core.data.Hash;
import convex.core.data.SignedData;
import convex.core.data.prim.CVMLong;
import convex.core.exceptions.BadFormatException;
import convex.core.message.AConnection;
import convex.core.message.Message;

/**
 * Authenticates physically inbound lattice connections before explicitly
 * upgrading them into outbound propagation routes.
 *
 * <p>Operator assignment to a {@link LatticePropagator} permits inbound lattice
 * access but does not authenticate the remote endpoint. This verifier preserves
 * that distinction: only a successful live challenge, followed by confirmation
 * that the proven key is an admitted desired peer, can call
 * {@link LatticeConnectionManager#upgradeInboundConnection(AConnection)}.</p>
 */
final class LatticeInboundVerifier {

	private static final Logger log = LoggerFactory.getLogger(LatticeInboundVerifier.class.getName());
	private static final long TIMEOUT_MS = 5_000L;

	private record PendingVerification(CVMLong id, LatticePropagator owner,
			CompletableFuture<Message> future) {}

	private final NodeServer<?> server;
	private final SecureRandom random = new SecureRandom();
	private final ConcurrentHashMap<AConnection, PendingVerification> active =
		new ConcurrentHashMap<>();

	LatticeInboundVerifier(NodeServer<?> server) {
		this.server = server;
	}

	/** Starts at most one non-blocking authentication attempt per inbound connection. */
	void maybeStart(AConnection connection, LatticePropagator owner) {
		if (connection == null || owner == null || connection.isClosed()
				|| connection.isTrusted() || !connection.supportsMessage()) return;

		CVMLong id = connection.nextRequestID();
		CompletableFuture<Message> resultFuture = new CompletableFuture<>();
		PendingVerification pending = new PendingVerification(id, owner, resultFuture);
		if (active.putIfAbsent(connection, pending) != null) return;

		try {
			Thread.ofVirtual().name("Lattice inbound authentication").start(
				() -> verify(connection, pending));
		} catch (RuntimeException e) {
			active.remove(connection, pending);
		}
	}

	private void verify(AConnection connection, PendingVerification pending) {
		try {
			AKeyPair keyPair = server.getSigningKey();
			if (keyPair == null) return;

			Hash token = Blob.createRandom(random, 16).getHash();
			AccountKey ownKey = keyPair.getAccountKey();
			SignedData<ACell> signed = Message.signChallenge(keyPair, token, null, null);
			if (!connection.sendMessage(Message.createChallenge(pending.id(), signed))) return;

			Message resultMessage = pending.future().get(TIMEOUT_MS, TimeUnit.MILLISECONDS);
			try {
				resultMessage.getPayload(pending.owner().getStore());
			} catch (BadFormatException e) {
				log.debug("Bad lattice authentication response from {}: {}",
					connection.getRemoteAddress(), e.getMessage());
				return;
			}

			Result result = resultMessage.toResult();
			AccountKey remoteKey = Message.verifyChallengeResponse(
				result, token, ownKey, null, null);
			if (remoteKey == null) return;
			LatticeConnectionManager manager = pending.owner().getConnectionManager();
			if (!manager.isDesiredPeer(remoteKey)) {
				log.debug("Authenticated inbound key {} has no admitted node identity", remoteKey);
				return;
			}
			if (connection.isClosed()) return;

			// Trust is established only here. upgradeInboundConnection independently
			// asserts this binding before granting the outbound propagation capability.
			connection.setTrustedKey(remoteKey);
			manager.upgradeInboundConnection(connection);
		} catch (Exception e) {
			log.debug("Inbound lattice authentication failed for {}: {}",
				connection.getRemoteAddress(), e.getMessage());
		} finally {
			active.remove(connection, pending);
		}
	}

	/** Routes a correlated RESULT from the NodeServer dispatcher to its verifier. */
	boolean handleResult(Message message) {
		if (active.isEmpty()) return false;
		AConnection connection = message.getConnection();
		if (connection == null) return false;
		PendingVerification pending = active.get(connection);
		if (pending == null) return false;
		try {
			if (!pending.id().equals(message.getResultID())) return false;
		} catch (BadFormatException e) {
			return false;
		}
		pending.future().complete(message);
		return true;
	}

	/** Cancels verification state when NodeServer observes physical disconnect. */
	void forget(AConnection connection) {
		PendingVerification pending = active.remove(connection);
		if (pending != null) {
			pending.future().completeExceptionally(
				new IOException("Inbound connection closed during authentication"));
		}
	}

	/** Cancels every pending attempt during NodeServer shutdown. */
	void close() {
		for (AConnection connection : active.keySet()) forget(connection);
	}
}
