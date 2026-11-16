package convex.peer;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import convex.core.crypto.AKeyPair;
import convex.core.cvm.State;
import convex.core.data.AccountKey;
import convex.core.init.Init;

/**
 * Genesis States for test networks, each built once per list of peer keys and shared by
 * every peer launched on it, since building one runs all of its deployments.
 *
 * <p>Sharing one State between peers, each with a fresh store, is safe. A State is
 * immutable apart from caches of facts about its own value. A store decides what it
 * holds from its own contents, never from a status that a Ref or cell carries from
 * another store: {@code MemoryStore} and {@code EtchStore} look each hash up in
 * themselves before persisting, so every peer's store persists the whole State for
 * itself. {@link API#launchLocalPeers} shares one State between its peers in the same
 * way.</p>
 */
public final class GenesisStates {
	/** By the ordered list of keys: the first is the genesis key. */
	private static final ConcurrentHashMap<List<AccountKey>,State> STATES=new ConcurrentHashMap<>();

	private GenesisStates() {}

	/**
	 * Gets the genesis State of a network of the given peers, as
	 * {@link Init#createState(List)} builds it for their keys in order.
	 *
	 * @param peers Key pairs of the peers, the first holding the genesis key
	 * @return Genesis State, shared with every other caller
	 */
	public static State of(AKeyPair... peers) {
		ArrayList<AccountKey> keys=new ArrayList<>(peers.length);
		for (AKeyPair kp: peers) keys.add(kp.getAccountKey());
		return of(keys);
	}

	/**
	 * Gets the genesis State {@link Init#createState(List)} builds for the given keys.
	 *
	 * @param keys Peer keys, the first being the genesis key
	 * @return Genesis State, shared with every other caller
	 */
	public static State of(List<AccountKey> keys) {
		return STATES.computeIfAbsent(List.copyOf(keys),Init::createState);
	}
}
