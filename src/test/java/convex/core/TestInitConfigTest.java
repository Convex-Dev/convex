
package convex.core;


import convex.core.crypto.AKeyPair;
import convex.core.data.AccountKey;
import convex.core.data.Address;
import convex.core.init.InitConfigTest;


public class TestInitConfigTest {

    public static InitConfigTest INIT_CONFIG = InitConfigTest.create();

	public static final Address HERO_ADDRESS = INIT_CONFIG.getHeroAddress();

	public static final AKeyPair HERO_KEYPAIR = INIT_CONFIG.getHeroKeyPair();

	public static final Address VILLAIN_ADDRESS = INIT_CONFIG.getVillainAddress();

	public static final AKeyPair VILLAIN_KEYPAIR = INIT_CONFIG.getVillainKeyPair();

	public static final Address FIRST_PEER_ADDRESS = INIT_CONFIG.getPeerAddress(0);

	public static final AKeyPair FIRST_PEER_KEYPAIR = INIT_CONFIG.getPeerKeyPair(0);

    public static final AccountKey FIRST_PEER_KEY = INIT_CONFIG.getPeerKeyPair(0).getAccountKey();

}
