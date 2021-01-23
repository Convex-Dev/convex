package convex.core;

import static convex.test.Assertions.assertArgumentError;
import static convex.test.Assertions.assertFundsError;
import static convex.test.Assertions.assertStateError;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import convex.core.data.PeerStatus;
import convex.core.lang.Context;
import convex.core.lang.TestState;
import static convex.core.lang.TestState.step;
import static convex.core.lang.TestState.eval;

public class StakingTest {

	private static final String PS = "\"" + Init.FIRST_PEER.toHexString() + "\"";

	@Test
	public void testDelegatedStaking() {

	}
	
	Context<Object> CTX=TestState.INITIAL_CONTEXT.fork();

	@Test
	public void testStake() {
		Context<Object> ctx0 =CTX.fork();

		Context<Object> ctx1 = ctx0.setStake(Init.FIRST_PEER, 1000);
		PeerStatus ps1 = ctx1.getState().getPeer(Init.FIRST_PEER);
		assertEquals(1000L, ps1.getDelegatedStake());
		assertEquals(TestState.TOTAL_FUNDS, ctx1.getState().computeTotalFunds());

		// round tripping this should return to initial state precisely
		// since we are not consuming any juice here, or adjusting anything other than
		// stake positions
		Context<Object> ctx2 = ctx1.setStake(Init.FIRST_PEER, 0);
		assertEquals(ctx0.getState(), ctx2.getState());

		// test putting entire balance on stake
		Context<Object> ctx3 = step(ctx0, "(stake " + PS + " *balance*)");
		assertEquals(0L, ctx3.getBalance(Init.HERO));
		assertEquals(TestState.HERO_BALANCE, ctx3.getState().getPeer(Init.FIRST_PEER).getDelegatedStake(Init.HERO));

		// test putting too much balance
		assertFundsError(step(ctx0, "(stake " + PS + " (inc *balance*))"));
	}

	@Test
	public void testStakeReturns() {
		Context<Object> ctx0 = CTX.fork();
		assertEquals(1000L, (long) eval(ctx0, "(stake " + PS + " 1000)"));
	}

	@Test
	public void testBadStake() {
		Context<Object> ctx0 = CTX.fork();

		// not a peer, should be state error
		assertStateError(ctx0.setStake(Init.HERO_KP.getAccountKey(), 1000));

		// bad arguments, out of range
		assertArgumentError(ctx0.setStake(Init.FIRST_PEER, -1));
		assertArgumentError(ctx0.setStake(Init.FIRST_PEER, Long.MAX_VALUE));

		// insufficient funds for stake
		assertFundsError(ctx0.setStake(Init.FIRST_PEER, Constants.MAX_SUPPLY));
		assertFundsError(ctx0.setStake(Init.FIRST_PEER, ctx0.getBalance(Init.HERO) + 1));
	}
}
