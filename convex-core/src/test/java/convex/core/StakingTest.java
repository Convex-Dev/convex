package convex.core;

import static convex.test.Assertions.assertArgumentError;
import static convex.test.Assertions.assertFundsError;
import static convex.test.Assertions.assertCVMEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import convex.core.cvm.Context;
import convex.core.cvm.PeerStatus;
import convex.core.init.InitTest;
import convex.core.lang.ACVMTest;

public class StakingTest extends ACVMTest {
	@Test
	public void testDelegatedStaking() {

	}

	@Test
	public void testStake() {
		Context ctx0 =context();

		Context ctx1 = ctx0.setDelegatedStake(InitTest.FIRST_PEER_KEY, 1000);
		PeerStatus ps1 = ctx1.getState().getPeer(InitTest.FIRST_PEER_KEY);
		assertEquals(1000L, ps1.getDelegatedStake());

		// round tripping this should return to initial state precisely
		// since we are not consuming any juice here, or adjusting anything other than
		// stake positions
		Context ctx2 = ctx1.setDelegatedStake(InitTest.FIRST_PEER_KEY, 0);
		assertEquals(ctx0.getState(), ctx2.getState());

		// test putting almost entire balance on stake
		// Note: putting whole balance fails with juice error 
		Context ctx3 = step(ctx0, "(set-stake " + InitTest.FIRST_PEER_KEY + " (- *balance* 1000000))");
		assertEquals(1000000L, ctx3.getBalance(InitTest.HERO));
		assertEquals(HERO_BALANCE-1000000L, ctx3.getState().getPeer(InitTest.FIRST_PEER_KEY).getDelegatedStake(InitTest.HERO));

		// test putting too much balance
		assertFundsError(step(ctx0, "(set-stake " + InitTest.FIRST_PEER_KEY + " (inc *balance*))"));
	}

	@Test
	public void testStakeReturns() {
		Context ctx = context();
		ctx=exec(ctx,"(set-stake " + InitTest.FIRST_PEER_KEY + " 1000)");
		assertCVMEquals(1000L, ctx.getResult());
		assertEquals(HERO_BALANCE-1000,ctx.getBalance()); // check initial balance restored
		ctx=exec(ctx,"(set-stake " + InitTest.FIRST_PEER_KEY + " 0)");
		assertCVMEquals(-1000L, ctx.getResult());
		assertEquals(HERO_BALANCE,ctx.getBalance()); // check initial balance restored
	}

	@Test
	public void testBadStake() {
		Context ctx = context();

		// TODO: new test since HERO is now a peer manager
		// not a peer, should be state error
		//assertStateError(ctx0.setDelegatedStake(InitTest.HERO_KEYPAIR.getAccountKey(), 1000));

		// bad arguments, out of range
		assertArgumentError(ctx.setDelegatedStake(InitTest.FIRST_PEER_KEY, -1));
		assertArgumentError(ctx.setDelegatedStake(InitTest.FIRST_PEER_KEY, Long.MAX_VALUE));

		// insufficient funds for stake
		assertFundsError(ctx.setDelegatedStake(InitTest.FIRST_PEER_KEY, Constants.MAX_SUPPLY));
		assertFundsError(ctx.setDelegatedStake(InitTest.FIRST_PEER_KEY, HERO_BALANCE + 1));
	}
}
