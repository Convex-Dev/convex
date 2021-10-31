package convex.peer;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.api.Convex;
import convex.api.ConvexLocal;
import convex.core.State;
import convex.core.crypto.AKeyPair;
import convex.core.data.AccountKey;
import convex.core.data.Address;
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
			AKeyPair.createSeeded(11),
			AKeyPair.createSeeded(13),
			AKeyPair.createSeeded(17),
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
		VILLAIN=HERO.offset(1);
	}

	private void waitForLaunch() {
		if (SERVERS == null) {
			SERVERS=API.launchLocalPeers(PEER_KEYPAIRS, GENESIS_STATE);
			try {
				SERVER = SERVERS.get(0);
				CONVEX=Convex.connect(SERVER, HERO, HERO_KEYPAIR);
			} catch (Throwable t) {
				throw Utils.sneakyThrow(t);
			}
		}
		log.info("*** Test Network ready ***");
	}

	public static TestNetwork getInstance() {
		if (instance == null) {
			instance = new TestNetwork();
		}
		instance.waitForLaunch();
		return instance;
	}
}
