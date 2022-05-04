package convex.restapi;

/**
 * Tests for a baisc REST API calls
*/

import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import convex.api.Convex;
import convex.api.ConvexLocal;
import convex.core.crypto.AKeyPair;
import convex.core.data.Address;
import convex.core.util.Utils;
import convex.peer.TestNetwork;

public class RestAPITest {

	static Address ADDRESS;
	static final AKeyPair KEYPAIR = AKeyPair.generate();
	static final int PORT = 99099;

	private static TestNetwork network;

	@BeforeAll
	public static void init() {
		network =  TestNetwork.getInstance();
		synchronized(network.SERVER) {
			try {
				ADDRESS=network.CONVEX.createAccountSync(KEYPAIR.getAccountKey());
				network.CONVEX.transfer(ADDRESS, 1000000000L).get(1000,TimeUnit.MILLISECONDS);
				ConvexLocal convex = Convex.connect(network.SERVER, ADDRESS, KEYPAIR);
				APIServer.start(PORT, convex);
			} catch (Throwable e) {
				e.printStackTrace();
				throw Utils.sneakyThrow(e);
			}
		}
	}


}
