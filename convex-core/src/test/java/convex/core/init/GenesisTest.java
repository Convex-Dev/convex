package convex.core.init;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

import convex.core.Coin;
import convex.core.cvm.State;

public class GenesisTest {

	@Test
	public void testGenesis() {
		State genesis=Init.createState(Init.DEFAULT_GOV_KEY, Init.DEFAULT_GENESIS_KEY, List.of(Init.FIRST_PEER_KEY));
		// This changes because of updates to cvx files
		// assertEquals("b0e44f2a645abfa539f5b96b7a0eabb0f902866feaff0f7c12d1213e02333f13",genesis.getHash().toHexString());
		assertEquals("15dce0fe6903633b4ed01aa4452d7250901d5a29cbf7f2c5916c97535ee948da",genesis.getHash().toHexString());
		
		assertEquals(Init.FIRST_USER_KEY,genesis.getAccount(13).getAccountKey());
		
		assertEquals(Init.GENESIS_COINS,genesis.computeSupply()); // 1m CVM initially in genesis
		assertEquals(Coin.MAX_SUPPLY,genesis.computeTotalBalance());
		
		// Should be 128 reserved addresses plus two currencies and markets (USDF and GBPF)
		assertEquals(132,genesis.getAccounts().count());
	}
}
