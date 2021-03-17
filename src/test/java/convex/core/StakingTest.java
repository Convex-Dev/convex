package convex.core;

import static convex.test.Assertions.assertArgumentError;
import static convex.test.Assertions.assertFundsError;
import static convex.test.Assertions.assertStateError;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import convex.core.data.ACell;
import convex.core.data.PeerStatus;
import convex.core.lang.Context;
import convex.core.lang.TestState;
import static convex.core.lang.TestState.*;

public class StakingTest {

	@Test
	public void testDelegatedStaking() {

	}
	
	Context<ACell> CTX=TestState.INITIAL_CONTEXT.fork();

	@Test
	public void testStake() {
		Context<ACell> ctx0 =CTX.fork();

		Context<ACell> ctx1 = ctx0.setStake(Init.FIRST_PEER_KEY, 1000);
		PeerStatus ps1 = ctx1.getState().getPeer(Init.FIRST_PEER_KEY);
		assertEquals(1000L, ps1.getDelegatedStake());

		// round tripping this should return to initial state precisely
		// since we are not consuming any juice here, or adjusting anything other than
		// stake positions
		Context<ACell> ctx2 = ctx1.setStake(Init.FIRST_PEER_KEY, 0);
		assertEquals(ctx0.getState(), ctx2.getState());

		// test putting entire balance on stake
		Context<ACell> ctx3 = step(ctx0, "(stake " + Init.FIRST_PEER_KEY + " *balance*)");
		assertEquals(0L, ctx3.getBalance(Init.HERO));
		assertEquals(TestState.HERO_BALANCE, ctx3.getState().getPeer(Init.FIRST_PEER_KEY).getDelegatedStake(Init.HERO));

		// test putting too much balance
		assertFundsError(step(ctx0, "(stake " + Init.FIRST_PEER_KEY + " (inc *balance*))"));
	}

	@Test
	public void testStakeReturns() {
		Context<ACell> ctx0 = CTX.fork();
		assertEquals(1000L, evalL(ctx0, "(stake " + Init.FIRST_PEER_KEY + " 1000)"));
	}

	@Test
	public void testBadStake() {
		Context<ACell> ctx0 = CTX.fork();

		// not a peer, should be state error
		assertStateError(ctx0.setStake(Init.HERO_KP.getAccountKey(), 1000));

		// bad arguments, out of range
		assertArgumentError(ctx0.setStake(Init.FIRST_PEER_KEY, -1));
		assertArgumentError(ctx0.setStake(Init.FIRST_PEER_KEY, Long.MAX_VALUE));

		// insufficient funds for stake
		assertFundsError(ctx0.setStake(Init.FIRST_PEER_KEY, Constants.MAX_SUPPLY));
		assertFundsError(ctx0.setStake(Init.FIRST_PEER_KEY, ctx0.getBalance(Init.HERO) + 1));
	}
}
