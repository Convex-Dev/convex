package convex.peer;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.api.Convex;
import convex.api.ConvexLocal;
import convex.api.ConvexRemote;
import convex.core.Coin;
import convex.core.crypto.AKeyPair;
import convex.core.cvm.Address;
import convex.core.cvm.State;
import convex.core.data.AccountKey;
import convex.core.exceptions.ResultException;
import convex.core.init.Init;
import convex.core.util.Utils;

/**
 * Singleton server cluster instance
 */
public class TestNetwork {

	private static final Logger log = LoggerFactory.getLogger(TestNetwork.class.getName());

	public Server SERVER = null;

	private List<Server> SERVERS = null;

	public ConvexLocal CONVEX;

	// Deterministic keypairs
	public AKeyPair[] KEYPAIRS = new AKeyPair[] {
			AKeyPair.createSeeded(2),
			AKeyPair.createSeeded(3),
			AKeyPair.createSeeded(5),
			AKeyPair.createSeeded(7),
			AKeyPair.createSeeded(19),
	};

	public ArrayList<AKeyPair> PEER_KEYPAIRS=(ArrayList<AKeyPair>) Arrays.asList(KEYPAIRS).stream().collect(Collectors.toList());
	public ArrayList<AccountKey> PEER_KEYS=(ArrayList<AccountKey>) Arrays.asList(KEYPAIRS).stream().map(kp->kp.getAccountKey()).collect(Collectors.toList());

	public AKeyPair FIRST_PEER_KEYPAIR = KEYPAIRS[0];
	public AccountKey FIRST_PEER_KEY = FIRST_PEER_KEYPAIR.getAccountKey();

	public AKeyPair HERO_KEYPAIR = KEYPAIRS[0];
	public AKeyPair VILLAIN_KEYPAIR = KEYPAIRS[1];

	public AccountKey HERO_KEY = HERO_KEYPAIR.getAccountKey();

	public Address HERO;
	public Address VILLAIN;

	public State GENESIS_STATE;
	private static TestNetwork instance = null;

	private TestNetwork() {
		// Use fresh State
		GENESIS_STATE=Init.createState(PEER_KEYS);
		HERO=Address.create(Init.GENESIS_ADDRESS);
		VILLAIN=HERO.offset(2);
	}

	private void waitForLaunch() throws PeerException, InterruptedException {
		if (SERVERS == null) {
			SERVERS=API.launchLocalPeers(PEER_KEYPAIRS, GENESIS_STATE);
			SERVER = SERVERS.get(0);
			CONVEX=Convex.connect(SERVER, HERO, HERO_KEYPAIR);
		}
		log.debug("*** Test Network ready ***");
	}
	
	/**
	 * Gets a fresh client account on the test network with 1 Gold
	 * @return Convex Client instance
	 */
	public synchronized ConvexRemote getClient() {
		return getClient(AKeyPair.generate());
	}
	
	/**
	 * Gets a fresh client account on the test network with 1 Gold
	 * @param kp Key pair to use for client
	 * @return Convex Client instance
	 */
	public synchronized ConvexRemote getClient(AKeyPair kp) {
		try {
			TestNetwork network=this;
			Address addr=network.CONVEX.createAccountSync(kp.getAccountKey());
			network.CONVEX.transferSync(addr, Coin.GOLD);
			ConvexRemote client=Convex.connect(network.SERVER.getHostAddress(),addr,kp);
			return client;
		} catch (IOException | ResultException | TimeoutException t) {
			throw Utils.sneakyThrow(t);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return null;
		}
	}

	public static synchronized TestNetwork getInstance() {
		if (instance == null) {
			instance = new TestNetwork();
		}
		try {
			instance.waitForLaunch();
		} catch (Exception e) {
			throw Utils.sneakyThrow(e);
		} 
		return instance;
	}
}
