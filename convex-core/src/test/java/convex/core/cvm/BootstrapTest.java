package convex.core.cvm;

import static convex.test.Assertions.assertTrustError;
import static convex.test.Assertions.assertUndeclaredError;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.Test;

import convex.core.cpos.Block;
import convex.core.data.ACell;
import convex.core.data.SignedData;
import convex.core.data.prim.CVMBool;
import convex.core.data.prim.CVMLong;
import convex.core.init.Init;
import convex.core.init.InitTest;
import convex.core.lang.Core;
import convex.core.lang.Reader;

/**
 * Tests for the v1 bootstrap migration: end-to-end adoption of the upgrade
 * mechanism on a genesis state, entirely on-chain. See UPGRADE.md.
 */
public class BootstrapTest {

	// Shared static state: immutable, so safe to share across tests
	static final State INIT_STATE = InitTest.STATE;

	/** Timestamp of the initial test state */
	static final long TS = INIT_STATE.getTimestamp().longValue();

	static final long ACTIVATION = TS + 1000;

	/** Schedules the bootstrap exactly as the governance transaction does:
	 * by embedding the schedule-upgrade cell directly (no binding exists yet) */
	static State scheduleBootstrap(State s) {
		Context ctx = Core.SCHEDULE_UPGRADE.invoke(Context.create(s, Init.GOVERNANCE_ADDRESS),
				new ACell[] { CVMLong.create(ACTIVATION) });
		assertEquals(CVMLong.create(1), ctx.getResult());
		return ctx.getState();
	}

	@Test
	public void testMigrationStateDeltas() {
		// Apply the bootstrap migration directly and assert the EXACT state changes
		State scheduled = scheduleBootstrap(INIT_STATE);
		State migrated = Migrations.get(0).apply(scheduled);

		// v1 touches exactly two accounts: the core account (#8) and the fungible
		// library (add-mint fix). Every other account is identical.
		Address fungible = (Address) scheduled.lookupCNS("convex.fungible");
		assertNotNull(fungible);
		long n = scheduled.getAccounts().count();
		for (long i = 0; i < n; i++) {
			Address a = Address.create(i);
			if (a.equals(Core.CORE_ADDRESS) || a.equals(fungible)) continue;
			assertSame(scheduled.getAccount(a), migrated.getAccount(a), "Account " + a + " must be untouched");
		}

		// The fungible library's add-mint was actually redefined (environment changed)
		assertNotSame(scheduled.getAccount(fungible).getEnvironment(),
				migrated.getAccount(fungible).getEnvironment());

		// Peers, schedule and globals are untouched by the migration itself
		// (the watermark advance is the mechanism's job, not the migration's)
		assertSame(scheduled.getPeers(), migrated.getPeers());
		assertSame(scheduled.getSchedule(), migrated.getSchedule());
		assertSame(scheduled.getGlobals(), migrated.getGlobals());

		// Within #8: exactly the four bindings and their :static metadata added
		AccountStatus pre = scheduled.getAccount(Core.CORE_ADDRESS);
		AccountStatus post = migrated.getAccount(Core.CORE_ADDRESS);
		assertEquals(pre.getEnvironment().count() + 4, post.getEnvironment().count());
		assertEquals(pre.getMetadata().count() + 4, post.getMetadata().count());
		assertSame(Core.SCHEDULE_UPGRADE, post.getEnvironmentValue(Symbols.SCHEDULE_UPGRADE));
		assertSame(Core.UNSCHEDULE_UPGRADE, post.getEnvironmentValue(Symbols.UNSCHEDULE_UPGRADE));
		assertSame(Core.GENSYM, post.getEnvironmentValue(Symbols.GENSYM));
		assertSame(Core.CAT, post.getEnvironmentValue(Symbols.CAT));
		assertEquals(CVMBool.TRUE, post.getMetadata().get(Symbols.SCHEDULE_UPGRADE).get(Keywords.STATIC));
		assertEquals(CVMBool.TRUE, post.getMetadata().get(Symbols.UNSCHEDULE_UPGRADE).get(Keywords.STATIC));
		assertEquals(CVMBool.TRUE, post.getMetadata().get(Symbols.GENSYM).get(Keywords.STATIC));
		assertEquals(CVMBool.TRUE, post.getMetadata().get(Symbols.CAT).get(Keywords.STATIC));
		assertEquals(pre.getBalance(), post.getBalance());
		assertEquals(pre.getSequence(), post.getSequence());

		// Determinism: bit-identical on repeated application
		assertEquals(migrated.getHash(), Migrations.get(0).apply(scheduled).getHash());
	}

	@Test
	public void testBootstrapThroughBlock() {
		// Full path: schedule on-chain, then apply a real signed block at the
		// activation boundary using the real Migrations registry
		State scheduled = scheduleBootstrap(INIT_STATE);
		assertEquals(0L, scheduled.getProtocolVersion());

		Block b = Block.of(ACTIVATION);
		SignedData<Block> sb = InitTest.FIRST_PEER_KEYPAIR.signData(b);
		State post = scheduled.applyBlock(sb).getState();

		assertEquals(1L, post.getProtocolVersion());
		assertSame(Core.SCHEDULE_UPGRADE, post.getAccount(Core.CORE_ADDRESS).getEnvironmentValue(Symbols.SCHEDULE_UPGRADE));
		StateTest.doStateTests(post);
	}

	@Test
	public void testSymbolResolutionAfterBootstrap() {
		// Pre-v1: the symbol does not resolve from source
		Context pre = Context.create(INIT_STATE, Init.GOVERNANCE_ADDRESS)
				.eval(Reader.read("(schedule-upgrade 999999999999999)"));
		assertUndeclaredError(pre);

		// Post-v1: resolves normally; governance can schedule the next upgrade
		State scheduled = scheduleBootstrap(INIT_STATE);
		State post = scheduled.applyUpgrades(ACTIVATION, Migrations::get).applyTimeUpdate(ACTIVATION);

		Context ok = Context.create(post, Init.GOVERNANCE_ADDRESS)
				.eval(Reader.read("(schedule-upgrade " + (ACTIVATION + 5000) + ")"));
		assertEquals(CVMLong.create(2), ok.getResult());

		// Gate still applies through normal CVM invocation
		Context bad = Context.create(post, InitTest.HERO)
				.eval(Reader.read("(schedule-upgrade " + (ACTIVATION + 5000) + ")"));
		assertTrustError(bad);

		// unschedule-upgrade resolves too
		Context un = Context.create(ok.getState(), Init.GOVERNANCE_ADDRESS)
				.eval(Reader.read("(unschedule-upgrade 2)"));
		assertEquals(CVMLong.create(ACTIVATION + 5000), un.getResult());
		assertEquals(1L, un.getState().getUpgradeVector().count());
		StateTest.doStateTests(un.getState());
	}
}
