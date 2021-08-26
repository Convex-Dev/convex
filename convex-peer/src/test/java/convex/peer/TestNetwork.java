package convex.peer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.junit.jupiter.api.Test;

import convex.api.Convex;
import convex.core.Belief;
import convex.core.Coin;
import convex.core.ErrorCodes;
import convex.core.Result;
import convex.core.State;
import convex.core.crypto.AKeyPair;
import convex.core.data.AVector;
import convex.core.data.AccountKey;
import convex.core.data.Address;
import convex.core.data.Hash;
import convex.core.data.Keyword;
import convex.core.data.Keywords;
import convex.core.data.Ref;
import convex.core.data.SignedData;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;
import convex.core.data.Maps;
import convex.core.exceptions.BadSignatureException;
import convex.core.init.Init;
import convex.core.transactions.*;
import convex.core.lang.Reader;
import convex.core.lang.RT;
import convex.core.lang.Symbols;
import convex.core.store.AStore;
import convex.core.store.Stores;
import convex.core.transactions.ATransaction;
import convex.core.util.Utils;
import convex.net.Connection;
import convex.net.Message;
import convex.net.ResultConsumer;
import etch.EtchStore;

/**
 * Tests for a fresh standalone server cluster instance
 */
public class TestNetwork {

	private static final Logger log = LoggerFactory.getLogger(TestNetwork.class.getName());

	private static long NETWORK_WAIT_TIMEOUT = 30 * 1000;
	public Server SERVER = null;

	private List<Server> SERVERS = null;

	public Convex CONVEX;

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
				// Thread.sleep(1000);
				API.isNetworkReady(SERVERS, NETWORK_WAIT_TIMEOUT);
				SERVER = SERVERS.get(0);
				CONVEX=Convex.connect(SERVER.getHostAddress(), HERO, HERO_KEYPAIR);
			} catch (Throwable t) {
				throw Utils.sneakyThrow(t);
			}
		}
		API.isNetworkReady(SERVERS, NETWORK_WAIT_TIMEOUT);
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
