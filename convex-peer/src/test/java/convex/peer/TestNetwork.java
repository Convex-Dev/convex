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
import convex.core.Result;
import convex.core.crypto.AKeyPair;
import convex.core.cvm.Address;
import convex.core.cvm.Migrations;
import convex.core.cvm.State;
import convex.core.data.AccountKey;
import convex.core.exceptions.ResultException;
import convex.core.init.Init;
import convex.core.util.Utils;

/**
 * Singleton server cluster instance, shared by test classes running concurrently.
 *
 * <p>Each test transacts from an account of its own, from {@link #getClient()},
 * {@link #getLocalClient()} or {@link #createAccount(AccountKey, long)}. The genesis
 * account HERO funds them: its transactions are made only here, one after another under
 * this network's monitor, so they are always in sequence.</p>
 */
public class TestNetwork {

	private static final Logger log = LoggerFactory.getLogger(TestNetwork.class.getName());

	public Server SERVER = null;

	private List<Server> SERVERS = null;

	/** Shared client for HERO, for queries and requests only: tests never transact with it. */
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
		// Fresh state at the LIVE protocol version (Migrations.LIVE_VERSION): the
		// shared test network models the live network, so a release cannot break
		// live servers that have not yet applied the latest upgrade. Follows the
		// live network automatically when LIVE_VERSION is bumped. Target-version
		// peer behaviour is covered by dedicated tests (e.g. UpgradeWithdrawalTest).
		// See UPGRADE.md, "Default test state policy".
		GENESIS_STATE=Migrations.applyTo(Init.createState(PEER_KEYS), Migrations.LIVE_VERSION);
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
	public ConvexRemote getClient() {
		return getClient(AKeyPair.generate());
	}
	
	/**
	 * Gets a fresh client account on the test network with 1 Gold
	 * @param kp Key pair to use for client
	 * @return Convex Client instance
	 */
	public ConvexRemote getClient(AKeyPair kp) {
		try {
			Address addr=createAccount(kp.getAccountKey(),Coin.GOLD);
			return Convex.connect(SERVER.getHostAddress(),addr,kp);
		} catch (IOException | ResultException | TimeoutException t) {
			throw Utils.sneakyThrow(t);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return null;
		}
	}
	
	/**
	 * Gets a fresh local client account on the test network with 1 Gold
	 * @return ConvexLocal Client instance
	 */
	public ConvexLocal getLocalClient() {
		return getLocalClient(AKeyPair.generate());
	}
	
	/**
	 * Gets a fresh local client account on the test network with 1 Gold
	 * @param kp Key pair to use for client
	 * @return ConvexLocal Client instance
	 */
	public ConvexLocal getLocalClient(AKeyPair kp) {
		try {
			Address addr=createAccount(kp.getAccountKey(),Coin.GOLD);
			return Convex.connect(SERVER,addr,kp);
		} catch (ResultException t) {
			throw Utils.sneakyThrow(t);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return null;
		}
	}

	/**
	 * Creates an account for a key, funded by HERO. Every HERO transaction is made
	 * here, one after another, so none of them races another for a sequence number.
	 * @param key Key of the new account
	 * @param amount Coins to transfer to it
	 * @return Address of the new account
	 * @throws ResultException if creating or funding the account fails
	 */
	public synchronized Address createAccount(AccountKey key,long amount) throws ResultException, InterruptedException {
		Address addr=CONVEX.createAccountSync(key);
		Result funded=CONVEX.transferSync(addr,amount);
		if (funded.isError()) throw new ResultException(funded);
		return addr;
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
