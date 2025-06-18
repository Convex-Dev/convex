package convex.core.init;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

import convex.core.Coin;
import convex.core.cvm.State;

public class GenesisTest {

	@Test
	public static void testGenesis() {
		State genesis=Init.createState(Init.DEFAULT_GOV_KEY, Init.DEFAULT_GENESIS_KEY, List.of(Init.FIRST_PEER_KEY));
		assertEquals("b0e44f2a645abfa539f5b96b7a0eabb0f902866feaff0f7c12d1213e02333f13",genesis.getHash().toHexString());
		
		assertEquals(Init.FIRST_USER_KEY,genesis.getAccount(13).getAccountKey());
		
		assertEquals(Coin.MAX_SUPPLY,genesis.computeSupply());
		
		// Should be 128 reserved addresses plus two currencies and markets (USDF and GBPF)
		assertEquals(132,genesis.getAccounts().count());
	}
}
