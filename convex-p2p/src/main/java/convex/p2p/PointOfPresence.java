package convex.p2p;

import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.core.crypto.AKeyPair;
import convex.core.crypto.ECIES;
import convex.core.cvm.CVMEncoder;
import convex.core.data.ACell;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.AccountKey;
import convex.core.data.Blob;
import convex.core.data.Format;
import convex.core.data.Hash;
import convex.core.data.SignedData;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMBool;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import convex.core.message.AConnection;
import convex.core.message.Message;
import convex.core.message.MessageType;
import convex.node.LatticeConnectionManager;
import convex.node.LatticePropagator;
import convex.node.NodeServer;
import convex.p2p.NodeDirectory.NodeRecord;

/**
 * Bounded end-to-end signed message routing over declared Points of Presence.
 *
 * <p>The signed envelope is invariant across relays. A mutable path is carried
 * outside the signature for honest loop avoidance and diagnostics; replay
 * protection is keyed by the signed envelope hash, so a relay cannot evade it by
 * rewriting that path. Relays send only over routes whose remote node key has
 * passed challenge/response.</p>
 */
final class PointOfPresence {

	private static final Logger log=LoggerFactory.getLogger(PointOfPresence.class);

	/** Application-level wire tag. Core peers safely classify this as UNKNOWN. */
	static final convex.core.data.Keyword TAG=convex.core.data.Keyword.intern("POP");

	/** Domain separator included in every signed envelope. */
	static final AString CONTEXT=Strings.intern("convex-pop-message-v1");

	static final int VERSION=1;
	static final int NONCE_LENGTH=16;
	static final int MAX_HOPS=8;
	static final int MAX_RELAY_FANOUT=3;
	static final int MAX_MESSAGE_SIZE=256*1024;
	static final int MAX_SEEN_MESSAGES=4096;
	static final int MAX_MESSAGES_PER_CONNECTION_SECOND=64;
	static final int MAX_MESSAGES_PER_NODE_SECOND=1024;
	static final long DEFAULT_TTL=60_000L;
	static final long MAX_TTL=5*60_000L;
	static final long MAX_CLOCK_SKEW=30_000L;

	private static final SecureRandom RANDOM=new SecureRandom();

	private final NodeServer<?> server;
	private final LatticePropagator propagator;
	private final NodeDirectory directory;
	private final AKeyPair keyPair;
	private final AccountKey ownKey;
	private volatile boolean relay;
	private volatile Consumer<P2PNode.ReceivedMessage> messageHandler;
	private final HashMap<AConnection,RateWindow> connectionRates=new HashMap<>();
	private long nodeRateWindowStart;
	private int nodeRateCount;

	/** Access-ordered, hard-bounded replay cache. */
	private final LinkedHashMap<Hash,Boolean> seenMessages=
		new LinkedHashMap<>(MAX_SEEN_MESSAGES,0.75f,true) {
			private static final long serialVersionUID=1L;

			@Override
			protected boolean removeEldestEntry(Map.Entry<Hash,Boolean> eldest) {
				return size()>MAX_SEEN_MESSAGES;
			}
		};

	PointOfPresence(NodeServer<?> server,LatticePropagator propagator,AKeyPair keyPair,
			NodeDirectory directory) {
		this.server=server;
		this.propagator=propagator;
		this.keyPair=keyPair;
		this.directory=directory;
		this.ownKey=(keyPair==null) ? null : keyPair.getAccountKey();
	}

	void setRelay(boolean relay) {
		this.relay=relay;
	}

	void setMessageHandler(Consumer<P2PNode.ReceivedMessage> handler) {
		this.messageHandler=handler;
	}

	boolean send(AccountKey destination,ACell payload,boolean encrypted) {
		if (ownKey==null) throw new IllegalStateException("Point messages require a node signing key");
		if (!server.isRunning()) throw new IllegalStateException("Node must be launched before sending messages");
		if (destination==null) throw new IllegalArgumentException("Destination node key must not be null");

		ACell body=payload;
		if (encrypted) {
			Blob plaintext=Format.encodeMultiCell(payload,true);
			if (plaintext.count()>MAX_MESSAGE_SIZE-ECIES.OVERHEAD) {
				throw new IllegalArgumentException("Private point-message payload is too large");
			}
			body=ECIES.encrypt(destination,plaintext);
		}

		long now=System.currentTimeMillis();
		AVector<ACell> envelope=Vectors.create(
			CONTEXT,
			CVMLong.create(VERSION),
			destination,
			Blob.createRandom(RANDOM,NONCE_LENGTH),
			CVMLong.create(now),
			CVMLong.create(now+DEFAULT_TTL),
			CVMLong.create(MAX_HOPS),
			CVMBool.create(encrypted),
			body);
		SignedData<AVector<ACell>> signed=keyPair.signData(envelope);
		AVector<AccountKey> path=Vectors.of(ownKey);
		Message message=createWireMessage(signed,path);
		if (message.getMessageData().count()>MAX_MESSAGE_SIZE) {
			throw new IllegalArgumentException("Point message exceeds "+MAX_MESSAGE_SIZE+" bytes");
		}

		markSeen(signed.getHash());
		Envelope parsed=new Envelope(signed,ownKey,destination,now,now+DEFAULT_TTL,
			MAX_HOPS,encrypted,body);
		if (destination.equals(ownKey)) return deliver(parsed);
		return route(parsed,path,message);
	}

	/** Handles one complete UNKNOWN application message on this propagator's endpoint. */
	boolean handle(Message message) {
		try {
			if (message.getMessageData().count()>MAX_MESSAGE_SIZE) return false;
			AVector<?> wire=RT.ensureVector(message.getPayload());
			if (wire==null || wire.count()!=3 || !TAG.equals(wire.get(0))) return false;
			if (!allowInbound(message.getConnection())) return false;
			if (!(wire.get(1) instanceof SignedData<?> rawSigned)) return false;
			if (!(rawSigned.getValue() instanceof AVector<?> rawEnvelope)) return false;
			AVector<?> rawPath=RT.ensureVector(wire.get(2));
			if (rawPath==null) return false;

			Envelope envelope=parseEnvelope(rawSigned,rawEnvelope);
			if (envelope==null) return false;
			AVector<AccountKey> path=parsePath(rawPath,envelope.sender(),envelope.maxHops());
			if (path==null) return false;

			AConnection connection=message.getConnection();
			AccountKey previous=path.get(path.count()-1);
			if (connection!=null && connection.isTrusted()
					&& !previous.equals(connection.getTrustedKey())) return false;
			Hash messageID=rawSigned.getHash();
			if (isSeen(messageID) || !rawSigned.checkSignature()) return false;
			if (!markSeen(messageID)) return false;

			if (ownKey!=null && envelope.destination().equals(ownKey)) {
				return deliver(envelope);
			}
			if (!relay || ownKey==null || path.count()>=envelope.maxHops()
					|| contains(path,ownKey)) return true;

			AVector<AccountKey> forwardedPath=path.conj(ownKey);
			return route(envelope,forwardedPath,createWireMessage(envelope.signed(),forwardedPath));
		} catch (Exception e) {
			log.debug("Rejected malformed point message: {}",e.getMessage());
			return false;
		}
	}

	@SuppressWarnings("unchecked")
	private static Envelope parseEnvelope(SignedData<?> rawSigned,AVector<?> value) {
		if (value.count()!=9 || !CONTEXT.equals(value.get(0))) return null;
		CVMLong version=RT.ensureLong(value.get(1));
		AccountKey destination=RT.ensureAccountKey(value.get(2));
		Blob nonce=(value.get(3) instanceof Blob blob) ? blob : null;
		CVMLong issued=RT.ensureLong(value.get(4));
		CVMLong expires=RT.ensureLong(value.get(5));
		CVMLong hops=RT.ensureLong(value.get(6));
		CVMBool privateFlag=(value.get(7) instanceof CVMBool flag) ? flag : null;
		if (version==null || version.longValue()!=VERSION || destination==null
				|| nonce==null || nonce.count()!=NONCE_LENGTH || issued==null
				|| expires==null || hops==null || privateFlag==null) return null;

		long issuedAt=issued.longValue();
		long expiresAt=expires.longValue();
		long maxHops=hops.longValue();
		long now=System.currentTimeMillis();
		if (issuedAt>now+MAX_CLOCK_SKEW || expiresAt<=now || expiresAt<=issuedAt
				|| expiresAt-issuedAt>MAX_TTL || maxHops<1 || maxHops>MAX_HOPS) return null;
		ACell body=value.get(8);
		if (privateFlag.booleanValue()
				&& (!(body instanceof Blob blob) || blob.count()<ECIES.OVERHEAD)) return null;

		SignedData<AVector<ACell>> signed=(SignedData<AVector<ACell>>)rawSigned;
		return new Envelope(signed,rawSigned.getAccountKey(),destination,issuedAt,expiresAt,
			(int)maxHops,privateFlag.booleanValue(),body);
	}

	private static AVector<AccountKey> parsePath(AVector<?> rawPath,AccountKey sender,int maxHops) {
		long count=rawPath.count();
		if (count<1 || count>maxHops) return null;
		HashSet<AccountKey> unique=new HashSet<>();
		AVector<AccountKey> path=Vectors.empty();
		for (long i=0; i<count; i++) {
			AccountKey key=RT.ensureAccountKey(rawPath.get(i));
			if (key==null || !unique.add(key)) return null;
			if (i==0 && !key.equals(sender)) return null;
			path=path.conj(key);
		}
		return path;
	}

	private boolean deliver(Envelope envelope) {
		ACell payload=envelope.body();
		if (envelope.encrypted()) {
			try {
				Blob plaintext=ECIES.decrypt(keyPair,(Blob)payload);
				payload=CVMEncoder.INSTANCE.decodeMultiCell(plaintext);
			} catch (Exception e) {
				log.debug("Unable to decrypt private point message from {}: {}",
					envelope.sender(),e.getMessage());
				return false;
			}
		}
		Consumer<P2PNode.ReceivedMessage> handler=messageHandler;
		if (handler==null) return true;
		try {
			handler.accept(new P2PNode.ReceivedMessage(envelope.signed().getHash(),
				envelope.sender(),envelope.destination(),payload,envelope.encrypted()));
		} catch (RuntimeException e) {
			log.warn("Point-message handler failed",e);
		}
		return true;
	}

	private boolean route(Envelope envelope,AVector<AccountKey> path,Message message) {
		LatticeConnectionManager manager=propagator.getConnectionManager();
		Set<AccountKey> routes=manager.getAuthenticatedRouteKeys();
		routes.removeAll(toSet(path));
		AccountKey destination=envelope.destination();
		if (routes.contains(destination)) {
			return manager.trySendAuthenticated(destination,message);
		}

		Map<AccountKey,NodeRecord> peers=directory.records();
		ArrayList<RouteCandidate> candidates=new ArrayList<>();
		for (AccountKey candidate:routes) {
			NodeRecord peer=peers.get(candidate);
			if (peer==null || !peer.relay()) continue;
			int distance=distance(candidate,destination,peers,toSet(path),
				envelope.maxHops()-(int)path.count());
			candidates.add(new RouteCandidate(candidate,distance));
		}
		candidates.sort(Comparator
			.comparingInt(RouteCandidate::distance)
			.thenComparing(RouteCandidate::key));

		boolean sent=false;
		int fanout=0;
		for (RouteCandidate candidate:candidates) {
			if (fanout>=MAX_RELAY_FANOUT) break;
			if (manager.trySendAuthenticated(candidate.key(),message)) sent=true;
			fanout++;
		}
		return sent;
	}

	/** Returns a bounded advertised-graph distance, or a large fallback score. */
	private static int distance(AccountKey start,AccountKey destination,
			Map<AccountKey,NodeRecord> peers,Set<AccountKey> blocked,int remainingHops) {
		if (remainingHops<1) return Integer.MAX_VALUE;
		ArrayDeque<RouteCandidate> queue=new ArrayDeque<>();
		HashSet<AccountKey> visited=new HashSet<>(blocked);
		visited.add(start);
		queue.add(new RouteCandidate(start,0));
		while (!queue.isEmpty()) {
			RouteCandidate current=queue.removeFirst();
			if (current.distance()>=remainingHops) continue;
			for (AccountKey next:neighbours(current.key(),peers)) {
				if (!visited.add(next)) continue;
				int nextDistance=current.distance()+1;
				if (next.equals(destination)) return nextDistance;
				NodeRecord peer=peers.get(next);
				if (peer!=null && peer.relay()) queue.addLast(new RouteCandidate(next,nextDistance));
			}
		}
		return Integer.MAX_VALUE;
	}

	private static Set<AccountKey> neighbours(AccountKey key,Map<AccountKey,NodeRecord> peers) {
		HashSet<AccountKey> result=new HashSet<>();
		NodeRecord own=peers.get(key);
		if (own!=null) addAll(result,own.pointsOfPresence());
		for (NodeRecord peer:peers.values()) {
			if (contains(peer.pointsOfPresence(),key)) result.add(peer.peerKey());
		}
		return result;
	}

	private static void addAll(Set<AccountKey> target,AVector<AccountKey> values) {
		if (values==null) return;
		for (long i=0; i<values.count(); i++) target.add(values.get(i));
	}

	private static Set<AccountKey> toSet(AVector<AccountKey> values) {
		HashSet<AccountKey> result=new HashSet<>();
		addAll(result,values);
		return result;
	}

	private static boolean contains(AVector<AccountKey> values,AccountKey key) {
		if (values==null) return false;
		for (long i=0; i<values.count(); i++) {
			if (key.equals(values.get(i))) return true;
		}
		return false;
	}

	private synchronized boolean markSeen(Hash id) {
		if (seenMessages.containsKey(id)) return false;
		seenMessages.put(id,Boolean.TRUE);
		return true;
	}

	private synchronized boolean isSeen(Hash id) {
		return seenMessages.containsKey(id);
	}

	/** Fixed-window guard evaluated on NodeServer's single inbound dispatcher. */
	private boolean allowInbound(AConnection connection) {
		if (connection==null) return true;
		long now=System.nanoTime();
		long second=1_000_000_000L;
		if (now-nodeRateWindowStart>=second) {
			nodeRateWindowStart=now;
			nodeRateCount=0;
		}
		if (++nodeRateCount>MAX_MESSAGES_PER_NODE_SECOND) return false;

		RateWindow window=connectionRates.computeIfAbsent(connection,key -> new RateWindow(now));
		if (now-window.started>=second) {
			window.started=now;
			window.count=0;
		}
		boolean allowed=++window.count<=MAX_MESSAGES_PER_CONNECTION_SECOND;
		if (connectionRates.size()>256) {
			connectionRates.entrySet().removeIf(entry -> entry.getKey().isClosed());
		}
		return allowed;
	}

	private static Message createWireMessage(SignedData<AVector<ACell>> signed,
			AVector<AccountKey> path) {
		return Message.create(MessageType.UNKNOWN,Vectors.create(TAG,signed,path));
	}

	private record Envelope(SignedData<AVector<ACell>> signed,AccountKey sender,
		AccountKey destination,long issuedAt,long expiresAt,int maxHops,
		boolean encrypted,ACell body) {}

	private record RouteCandidate(AccountKey key,int distance) {}

	private static final class RateWindow {
		long started;
		int count;

		RateWindow(long started) {
			this.started=started;
		}
	}
}
