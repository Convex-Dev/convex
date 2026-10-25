package convex.gui.wallet;

import java.awt.EventQueue;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;

import javax.swing.DefaultListModel;
import javax.swing.ListModel;

import convex.api.Convex;
import convex.core.ErrorCodes;
import convex.core.Result;
import convex.core.cvm.Address;
import convex.core.cvm.Keywords;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.Hash;
import convex.core.data.Keyword;
import convex.core.exceptions.ResultException;
import convex.core.lang.RT;
import convex.core.util.Utils;
import convex.peer.API;
import convex.peer.Server;

/**
 * GUI state belonging to one Convex network, shared by every window connected
 * to it: tracked tokens and the resolved addresses of on-chain libraries.
 * Networks are identified by genesis hash, so nothing leaks between networks
 * open in the same process.
 */
public final class NetworkState {

	private static final ConcurrentHashMap<Hash,NetworkState> NETWORKS=new ConcurrentHashMap<>();

	/** Genesis hash of each connection, so a remote connection asks only once */
	private static final Map<Convex,Hash> GENESIS=Collections.synchronizedMap(new WeakHashMap<>());

	private final Hash genesis;

	/** CNS name to library address. Only successful resolutions are kept. */
	private final ConcurrentHashMap<String,Address> libraries=new ConcurrentHashMap<>();

	/** Tracked tokens. Changed and read only on the EDT. */
	private final DefaultListModel<TokenInfo> tokens=new DefaultListModel<>();

	private NetworkState(Hash genesis) {
		this.genesis=genesis;
	}

	/**
	 * Gets the state for a network
	 * @param genesis Genesis hash of the network
	 * @return Network state, shared by everything on that network
	 */
	public static NetworkState forGenesis(Hash genesis) {
		return NETWORKS.computeIfAbsent(genesis, NetworkState::new);
	}

	/**
	 * Gets the state for the network a connection is on. For a remote peer this
	 * blocks on a status request the first time a connection is seen.
	 * @param convex Connection to a peer
	 * @return Network state
	 * @throws InterruptedException If interrupted waiting for the peer
	 * @throws ResultException If the peer does not report its genesis hash
	 */
	public static NetworkState of(Convex convex) throws InterruptedException, ResultException {
		return forGenesis(genesisHash(convex));
	}

	private static Hash genesisHash(Convex convex) throws InterruptedException, ResultException {
		Server server=convex.getLocalServer();
		if (server!=null) return server.getPeer().getGenesisHash();

		Hash known=GENESIS.get(convex);
		if (known!=null) return known;

		Result r=convex.requestStatusSync();
		if (r.isError()) throw new ResultException(r);
		AMap<Keyword,ACell> status=API.ensureStatusMap(r.getValue());
		Hash genesis=(status==null)?null:RT.ensureHash(status.get(Keywords.GENESIS));
		if (genesis==null) {
			throw new ResultException(Result.error(ErrorCodes.FORMAT, "Peer status has no genesis hash"));
		}
		GENESIS.put(convex, genesis);
		return genesis;
	}

	/**
	 * @return Genesis hash identifying this network
	 */
	public Hash getGenesis() {
		return genesis;
	}

	/**
	 * Gets the address of an on-chain library on this network, resolving it by
	 * CNS name the first time. Blocks while resolving.
	 * @param convex Connection to this network, used to resolve
	 * @param cnsName CNS name of the library, e.g. "convex.fungible"
	 * @return Library address, or null if it could not be resolved
	 * @throws InterruptedException If interrupted while resolving
	 */
	public Address resolveLibrary(Convex convex, String cnsName) throws InterruptedException {
		Address known=libraries.get(cnsName);
		if (known!=null) return known;
		try {
			Address resolved=RT.ensureAddress(convex.resolve(cnsName).get());
			if (resolved!=null) libraries.putIfAbsent(cnsName, resolved);
			return resolved;
		} catch (ExecutionException e) {
			return null;
		}
	}

	/**
	 * Gets the tracked tokens, for display. Read only on the EDT.
	 * @return List model of tracked tokens
	 */
	public ListModel<TokenInfo> trackedTokens() {
		return tokens;
	}

	/**
	 * Starts tracking a token, unless already tracked. Safe from any thread.
	 * @param token Token to track (ignored if null)
	 */
	public void track(TokenInfo token) {
		if (token==null) return;
		EventQueue.invokeLater(()->{
			if (!tokens.contains(token)) tokens.addElement(token);
		});
	}

	/**
	 * Stops tracking a token. Safe from any thread.
	 * @param token Token to stop tracking
	 */
	public void untrack(TokenInfo token) {
		EventQueue.invokeLater(()->tokens.removeElement(token));
	}

	/**
	 * Checks whether a token is tracked. Call only on the EDT.
	 * @param token Token to check
	 * @return true if tracked
	 */
	public boolean isTracked(TokenInfo token) {
		return tokens.contains(token);
	}

	/**
	 * Gets the "other" token for a trade, which will be:
	 * 1. The default token, if the token passed is not the default
	 * 2. The next other tracked token, if it is
	 * 3. Null otherwise (no other token available)
	 * Call only on the EDT.
	 * @param token Token selected for swap
	 * @return TokenInfo for other token to trade with
	 */
	public TokenInfo otherToken(TokenInfo token) {
		TokenInfo other=TokenListPanel.getDefaultToken();
		if (!Utils.equals(other.getID(),token.getID())) return other;
		int n=tokens.getSize();
		for (int i=0; i<n; i++) {
			other=tokens.elementAt(i);
			if (!Utils.equals(other.getID(),token.getID())) return other;
		}
		return null;
	}
}
