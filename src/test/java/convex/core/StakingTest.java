package convex.core;

import static convex.test.Assertions.assertArgumentError;
import static convex.test.Assertions.assertFundsError;
import static convex.test.Assertions.assertStateError;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import convex.core.data.ACell;
import convex.core.data.AccountKey;
import convex.core.data.PeerStatus;
import convex.core.lang.ACVMTest;
import convex.core.lang.Context;

public class StakingTest extends ACVMTest {

	protected StakingTest() {
		super(Init.createState());
	}

	AccountKey FIRST_PEER_KEY=Init.KEYPAIRS[0].getAccountKey();
	
	@Test
	public void testDelegatedStaking() {

	}

	@Test
	public void testStake() {
		Context<ACell> ctx0 =context();

		Context<ACell> ctx1 = ctx0.setStake(FIRST_PEER_KEY, 1000);
		PeerStatus ps1 = ctx1.getState().getPeer(FIRST_PEER_KEY);
		assertEquals(1000L, ps1.getDelegatedStake());

		// round tripping this should return to initial state precisely
		// since we are not consuming any juice here, or adjusting anything other than
		// stake positions
		Context<ACell> ctx2 = ctx1.setStake(FIRST_PEER_KEY, 0);
		assertEquals(ctx0.getState(), ctx2.getState());

		// test putting entire balance on stake
		Context<ACell> ctx3 = step(ctx0, "(stake " + FIRST_PEER_KEY + " *balance*)");
		assertEquals(0L, ctx3.getBalance(Init.HERO));
		assertEquals(HERO_BALANCE, ctx3.getState().getPeer(FIRST_PEER_KEY).getDelegatedStake(Init.HERO));

		// test putting too much balance
		assertFundsError(step(ctx0, "(stake " + FIRST_PEER_KEY + " (inc *balance*))"));
	}

	@Test
	public void testStakeReturns() {
		Context<ACell> ctx0 = context();
		assertEquals(1000L, evalL(ctx0, "(stake " + FIRST_PEER_KEY + " 1000)"));
	}

	@Test
	public void testBadStake() {
		Context<ACell> ctx0 = context();

		// not a peer, should be state error
		assertStateError(ctx0.setStake(Init.HERO_KP.getAccountKey(), 1000));

		// bad arguments, out of range
		assertArgumentError(ctx0.setStake(FIRST_PEER_KEY, -1));
		assertArgumentError(ctx0.setStake(FIRST_PEER_KEY, Long.MAX_VALUE));

		// insufficient funds for stake
		assertFundsError(ctx0.setStake(FIRST_PEER_KEY, Constants.MAX_SUPPLY));
		assertFundsError(ctx0.setStake(FIRST_PEER_KEY, ctx0.getBalance(Init.HERO) + 1));
	}
}
