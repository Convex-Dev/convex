package convex.core.lang;

import static convex.test.Assertions.assertArgumentError;
import static convex.test.Assertions.assertArityError;
import static convex.test.Assertions.assertCastError;
import static convex.test.Assertions.assertTrustError;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.Test;

import convex.core.cvm.Address;
import convex.core.cvm.Context;
import convex.core.cvm.Migrations;
import convex.core.cvm.State;
import convex.core.cvm.StateTest;
import convex.core.cvm.Symbols;
import convex.core.data.ACell;
import convex.core.data.Symbol;
import convex.core.data.Cells;
import convex.core.data.prim.CVMLong;
import convex.core.init.Init;
import convex.core.init.InitTest;
import convex.test.Samples;

/**
 * Tests for the schedule-upgrade / unschedule-upgrade core functions.
 * See UPGRADE.md.
 *
 * These are deliberately NOT in any environment pre-v1, so tests invoke the
 * CoreFn cells directly — exactly how the bootstrap scheduling transaction
 * embeds them in compiled code.
 */
public class UpgradeSchedulingTest {

	// Shared static state: immutable, so safe to share across tests
	static final State INIT_STATE = InitTest.STATE;

	/** Timestamp of the initial test state */
	static final long TS = INIT_STATE.getTimestamp().longValue();

	static final Address GOVERNANCE = Init.GOVERNANCE_ADDRESS; // #6
	static final Address ADMIN = Init.ADMIN_ADDRESS;           // #7

	static Context schedule(State s, Address origin, long activation) {
		Context ctx = Context.create(s, origin);
		return Core.SCHEDULE_UPGRADE.invoke(ctx, new ACell[] { CVMLong.create(activation) });
	}

	static Context unschedule(State s, Address origin, long version) {
		Context ctx = Context.create(s, origin);
		return Core.UNSCHEDULE_UPGRADE.invoke(ctx, new ACell[] { CVMLong.create(version) });
	}

	@Test
	public void testNotInGenesis() {
		// No environment binding, no implicit form: pre-v1 the only route to these
		// functions is embedding the cell directly in compiled code
		assertFalse(Core.ENVIRONMENT.containsKey(Symbols.SCHEDULE_UPGRADE));
		assertFalse(Core.ENVIRONMENT.containsKey(Symbols.UNSCHEDULE_UPGRADE));
		assertFalse(Core.CORE_FORMS.containsKey(Symbol.create("#%schedule-upgrade")));
		assertFalse(Core.CORE_FORMS.containsKey(Symbol.create("#%unschedule-upgrade")));

		// Genesis core account environment does not contain them either
		assertNull(INIT_STATE.getAccount(Core.CORE_ADDRESS).getEnvironmentValue(Symbols.SCHEDULE_UPGRADE));
		assertNull(INIT_STATE.getAccount(Core.CORE_ADDRESS).getEnvironmentValue(Symbols.UNSCHEDULE_UPGRADE));
	}

	@Test
	public void testEncodingRoundTrip() throws Exception {
		// Codes are statically registered: the cells are decodable singletons
		assertSame(Core.SCHEDULE_UPGRADE, Samples.TEST_STORE.decode(Cells.encode(Core.SCHEDULE_UPGRADE)));
		assertSame(Core.UNSCHEDULE_UPGRADE, Samples.TEST_STORE.decode(Cells.encode(Core.UNSCHEDULE_UPGRADE)));
	}

	@Test
	public void testGovernanceGate() {
		long activation = TS + 1000;

		// Ordinary accounts fail with :TRUST (no juice charged before the gate)
		assertTrustError(schedule(INIT_STATE, InitTest.HERO, activation));
		assertTrustError(unschedule(INIT_STATE, InitTest.HERO, 1));

		// The core account (#8) is the boundary: not a governance account
		assertTrustError(schedule(INIT_STATE, Core.CORE_ADDRESS, activation));

		// Governance accounts below #8 succeed
		Context ctx = schedule(INIT_STATE, GOVERNANCE, activation);
		assertEquals(CVMLong.create(1), ctx.getResult());

		Context ctx2 = schedule(INIT_STATE, ADMIN, activation);
		assertEquals(CVMLong.create(1), ctx2.getResult());
	}

	@Test
	public void testScheduling() {
		long t1 = TS + 1000;
		long t2 = TS + 2000;

		// First schedule extends the globals and returns the version it produces
		Context ctx = schedule(INIT_STATE, GOVERNANCE, t1);
		State s1 = ctx.getState();
		assertEquals(CVMLong.create(1), ctx.getResult());
		assertEquals(0L, s1.getProtocolVersion());
		assertEquals(1L, s1.getUpgradeVector().count());
		assertEquals(State.GLOBAL_UPGRADES + 1, s1.getGlobals().count());
		StateTest.doStateTests(s1);

		// Second schedule appends; equal timestamps are permitted
		Context ctx2 = schedule(s1, GOVERNANCE, t2);
		State s2 = ctx2.getState();
		assertEquals(CVMLong.create(2), ctx2.getResult());
		assertEquals(2L, s2.getUpgradeVector().count());

		Context ctx3 = schedule(s2, GOVERNANCE, t2);
		assertEquals(CVMLong.create(3), ctx3.getResult());
		StateTest.doStateTests(ctx3.getState());

		// Validation: past or present activation rejected
		assertArgumentError(schedule(INIT_STATE, GOVERNANCE, TS));
		assertArgumentError(schedule(INIT_STATE, GOVERNANCE, TS - 1000));

		// Validation: decreasing activation rejected
		assertArgumentError(schedule(s2, GOVERNANCE, t1 + 500));

		// Cast and arity errors
		assertCastError(Core.SCHEDULE_UPGRADE.invoke(Context.create(INIT_STATE, GOVERNANCE),
				new ACell[] { convex.core.data.Strings.create("soon") }));
		assertArityError(Core.SCHEDULE_UPGRADE.invoke(Context.create(INIT_STATE, GOVERNANCE), new ACell[0]));
	}

	@Test
	public void testUnscheduling() {
		long t1 = TS + 1000;
		long t2 = TS + 2000;
		State s2 = schedule(schedule(INIT_STATE, GOVERNANCE, t1).getState(), GOVERNANCE, t2).getState();

		// Remove the tail pending entry; result is its activation timestamp
		Context ctx = unschedule(s2, GOVERNANCE, 2);
		State s1 = ctx.getState();
		assertEquals(CVMLong.create(t2), ctx.getResult());
		assertEquals(1L, s1.getUpgradeVector().count());
		StateTest.doStateTests(s1);

		// Only the tail may be removed
		assertArgumentError(unschedule(s2, GOVERNANCE, 1));
		assertArgumentError(unschedule(s2, GOVERNANCE, 3));

		// Nothing scheduled
		assertArgumentError(unschedule(INIT_STATE, GOVERNANCE, 1));

		// An applied upgrade cannot be unscheduled
		State applied = INIT_STATE.withProtocolGlobals(1,
				convex.core.data.Vectors.of(CVMLong.create(TS - 1000)));
		assertArgumentError(unschedule(applied, GOVERNANCE, 1));
	}

	@Test
	public void testValidationIsStateOnly() {
		// Scheduling must never consult the local Migrations registry: scheduling a
		// version this release cannot apply is valid and expected (older peers
		// schedule fine and stop at the transition block). See UPGRADE.md
		assertEquals(1L, Migrations.MAX_VERSION);
		assertNull(Migrations.get(1)); // no migration for version 2 in this release

		Context c1 = schedule(INIT_STATE, GOVERNANCE, TS + 1000);
		Context c2 = schedule(c1.getState(), GOVERNANCE, TS + 2000);
		assertEquals(CVMLong.create(2), c2.getResult()); // scheduled beyond supported version
	}

}
