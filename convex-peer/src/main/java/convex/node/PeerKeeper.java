package convex.node;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.LongSupplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.api.Convex;
import convex.core.data.AccountKey;
import convex.core.data.Vectors;
import convex.net.PeerName;
import convex.net.PeerResolver;
import convex.net.PeerTarget;

/**
 * Keeps a peer the operator named: resolves its name and connects; when the peer cannot
 * be reached, or its route goes, resolves the name again and reconnects after a backoff,
 * so a moved endpoint or a changed DID document is picked up. A peer that stays
 * unreachable for a whole period is given up and reported; each lost route starts a
 * new period.
 *
 * <p>A peer named by a transport URI without an expected key is held to the key it
 * first proves, as the manager holds desired intent to its key.</p>
 */
final class PeerKeeper {

	private static final Logger log=LoggerFactory.getLogger(PeerKeeper.class);

	/** Runs a task after a delay. */
	@FunctionalInterface
	interface Scheduler {
		void schedule(long delayMillis,Runnable task);
	}

	private final LatticeConnectionManager manager;
	private final PeerResolver resolver;
	private final PeerName name;
	private final long giveUpMillis;
	private final LongSupplier clock;
	private final Scheduler scheduler;

	/** Fails when the peer is given up; cancelling it stops keeping the peer. */
	final CompletableFuture<Void> kept=new CompletableFuture<>();

	/** Key first proved under a transport name with no expected key. */
	private AccountKey pinned;
	/** Key of the route currently kept, or null. */
	private AccountKey current;
	/** Start of the current period without success. */
	private long since;
	/** Failed attempts in the current period, for backoff. */
	private int failures;

	PeerKeeper(LatticeConnectionManager manager,PeerResolver resolver,PeerName name,long giveUpMillis,
			LongSupplier clock,Scheduler scheduler) {
		this.manager=manager;
		this.resolver=resolver;
		this.name=name;
		this.giveUpMillis=giveUpMillis;
		this.clock=clock;
		this.scheduler=scheduler;
		kept.whenComplete((value,error) -> {
			if (!kept.isCancelled()) return;
			AccountKey key;
			synchronized (this) {
				key=current;
				current=null;
			}
			if (key!=null) manager.releasePeer(key);
		});
	}

	/**
	 * Starts keeping the peer.
	 *
	 * @param admitted a connection to the peer already admitted, or null to connect first
	 */
	void start(Convex admitted) {
		synchronized (this) {
			since=clock.getAsLong();
		}
		if (admitted!=null) {
			connected(admitted);
		} else {
			attempt();
		}
	}

	private void attempt() {
		if (kept.isDone()) return;
		CompletableFuture<Convex> connecting;
		try {
			connecting=resolver.resolve(name).thenCompose(target -> manager.connectPeer(pinned(target)));
		} catch (RuntimeException e) {
			connecting=CompletableFuture.failedFuture(e);
		}
		connecting.whenComplete((peer,error) -> {
			if (error==null) {
				connected(peer);
			} else {
				failed(error);
			}
		});
	}

	private synchronized PeerTarget pinned(PeerTarget target) {
		if (pinned==null || !target.keys().isEmpty()) return target;
		return new PeerTarget(target.name(),Vectors.of(pinned),target.endpoints());
	}

	private void connected(Convex peer) {
		AccountKey key=peer.getVerifiedPeer();
		if (kept.isDone()) {
			// Stopped while connecting: the route is not kept
			if (key!=null) manager.releasePeer(key);
			return;
		}
		synchronized (this) {
			if (name.key()==null && !"did".equalsIgnoreCase(name.uri().getScheme())) pinned=key;
			current=key;
			failures=0;
		}
		log.info("Connected to peer {} as {}",name,key);
		manager.whenDisconnected(key).whenComplete((value,error) -> lost(key));
	}

	private void lost(AccountKey key) {
		synchronized (this) {
			if (current!=key) return;
			current=null;
			since=clock.getAsLong();
		}
		if (kept.isDone()) return;
		log.info("Lost the route to peer {}; resolving it again",name);
		attempt();
	}

	private void failed(Throwable error) {
		if (kept.isDone()) return;
		Throwable cause=(error instanceof CompletionException && error.getCause()!=null) ? error.getCause() : error;
		long elapsed;
		int failed;
		synchronized (this) {
			elapsed=clock.getAsLong()-since;
			failed=++failures;
		}
		if (elapsed>=giveUpMillis) {
			log.warn("Gave up peer {} after {} s without reaching it: {}",name,elapsed/1000,cause.getMessage());
			kept.completeExceptionally(new IllegalStateException("Gave up peer "+name+": "+cause.getMessage(),cause));
			return;
		}
		log.warn("Could not reach peer {}: {}; trying again",name,cause.getMessage());
		scheduler.schedule(LatticeConnectionManager.calculateBackoff(failed),this::attempt);
	}
}
