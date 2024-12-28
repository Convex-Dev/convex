package convex.core.init;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

import convex.core.cvm.State;

public class GenesisTest {

	@Test
	public static void testGenesis() {
		State genesis=Init.createState(Init.DEFAULT_GOV_KEY, Init.DEFAULT_GENESIS_KEY, List.of(Init.FIRST_PEER_KEY));
		assertEquals("b0e44f2a645abfa539f5b96b7a0eabb0f902866feaff0f7c12d1213e02333f13",genesis.getHash().toHexString());
	}
}
