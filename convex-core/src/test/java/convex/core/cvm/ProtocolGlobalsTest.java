package convex.core.cvm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestInstance.Lifecycle;
import org.opentest4j.AssertionFailedError;

import convex.core.data.AVector;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;
import convex.core.init.InitTest;

/**
 * Tests for the protocol globals: version watermark and upgrade vector.
 *
 * Design: see UPGRADE.md. Key property under test: genesis is never modified —
 * all states without the protocol globals read as version 0 with an empty
 * upgrade vector, and the globals vector is only extended by the upgrade
 * mechanism itself.
 */
@TestInstance(Lifecycle.PER_CLASS)
public class ProtocolGlobalsTest {

	// Shared static state: immutable, so safe to share across tests
	static final State INIT_STATE = InitTest.STATE;

	/** Timestamp of the initial test state */
	static final long TS = INIT_STATE.getTimestamp().longValue();

	static AVector<CVMLong> upgrades(long... activations) {
		AVector<CVMLong> v = Vectors.empty();
		for (long a : activations) {
			v = v.conj(CVMLong.create(a));
		}
		return v;
	}

	@Test
	public void testGenesisDefaults() {
		// Genesis carries no protocol globals: version 0, empty upgrade vector
		assertEquals(0L, INIT_STATE.getProtocolVersion());
		assertEquals(Vectors.empty(), INIT_STATE.getUpgradeVector());

		// Lock in the decision that Init output is unchanged: still 6 globals
		assertEquals(State.GLOBAL_PROTOCOL, INIT_STATE.getGlobals().count());
	}

	@Test
	public void testEmptyStateDefaults() {
		assertEquals(0L, State.EMPTY.getProtocolVersion());
		assertEquals(Vectors.empty(), State.EMPTY.getUpgradeVector());
	}

	@Test
	public void testRealGenesisDefaults() {
		// The committed genesis.cad3 (real network genesis) must read as version 0
		State genesis = GenesisStateTest.getGenesisState();
		assertEquals(0L, genesis.getProtocolVersion());
		assertEquals(Vectors.empty(), genesis.getUpgradeVector());
	}

	@Test
	public void testGlobalSymbols() {
		// One symbol per global index, including protocol and upgrades
		assertEquals(State.GLOBAL_UPGRADES + 1, State.GLOBAL_SYMBOLS.count());
		assertEquals(Symbols.PROTOCOL, State.GLOBAL_SYMBOLS.get(State.GLOBAL_PROTOCOL));
		assertEquals(Symbols.UPGRADES, State.GLOBAL_SYMBOLS.get(State.GLOBAL_UPGRADES));
	}

	@Test
	public void testWithProtocolGlobalsExtends() {
		// One applied upgrade, fired in the past
		AVector<CVMLong> ups = upgrades(TS - 1000);
		State s = INIT_STATE.withProtocolGlobals(1, ups);

		// Globals extended densely to include both new slots
		assertEquals(State.GLOBAL_UPGRADES + 1, s.getGlobals().count());
		assertEquals(1L, s.getProtocolVersion());
		assertEquals(ups, s.getUpgradeVector());

		// Nothing else in the State changes: untouched subtrees are identical
		assertSame(INIT_STATE.getAccounts(), s.getAccounts());
		assertSame(INIT_STATE.getPeers(), s.getPeers());
		assertSame(INIT_STATE.getSchedule(), s.getSchedule());

		// Pre-existing globals retain their values
		assertEquals(INIT_STATE.getTimestamp(), s.getTimestamp());
		assertEquals(INIT_STATE.getGlobalFees(), s.getGlobalFees());
		assertEquals(INIT_STATE.getJuicePrice(), s.getJuicePrice());
		assertEquals(INIT_STATE.getGlobalMemoryValue(), s.getGlobalMemoryValue());
		assertEquals(INIT_STATE.getGlobalMemoryPool(), s.getGlobalMemoryPool());
		assertEquals(INIT_STATE.getBlockNumber(), s.getBlockNumber());

		// State hash changes (globals changed), original untouched
		assertNotEquals(INIT_STATE.getHash(), s.getHash());
		assertEquals(0L, INIT_STATE.getProtocolVersion());

		StateTest.doStateTests(s);
	}

	@Test
	public void testWithProtocolGlobalsUpdate() {
		AVector<CVMLong> v1 = upgrades(TS - 2000);
		AVector<CVMLong> v2 = upgrades(TS - 2000, TS - 1000);

		// Update of an already-extended state: no further extension, values replaced
		State s1 = INIT_STATE.withProtocolGlobals(1, v1);
		State s2 = s1.withProtocolGlobals(2, v2);

		assertEquals(State.GLOBAL_UPGRADES + 1, s2.getGlobals().count());
		assertEquals(2L, s2.getProtocolVersion());
		assertEquals(v2, s2.getUpgradeVector());
		StateTest.doStateTests(s2);

		// Same values => same state
		State s1b = INIT_STATE.withProtocolGlobals(1, v1);
		assertEquals(s1.getHash(), s1b.getHash());
	}

	@Test
	public void testExtendedStateValid() throws Exception {
		// Mixed vector: one applied (past), one pending (future)
		State s = INIT_STATE.withProtocolGlobals(1, upgrades(TS - 1000, TS + 1000));

		// The extended state is fully valid, encodable and round-trippable
		s.validate();
		StateTest.doStateTests(s);
		assertTrue(s.getEncodingLength() > 0);
	}

	@Test
	public void testInvariantChecksBite() {
		// The generic invariant checker must reject malformed protocol globals

		// Watermark beyond upgrade vector count
		State overVersion = INIT_STATE.withProtocolGlobals(1, Vectors.empty());
		assertThrows(AssertionFailedError.class, () -> StateTest.doProtocolInvariantTests(overVersion));

		// Decreasing activations
		State decreasing = INIT_STATE.withProtocolGlobals(0, upgrades(TS + 2000, TS + 1000));
		assertThrows(AssertionFailedError.class, () -> StateTest.doProtocolInvariantTests(decreasing));

		// Pending activation not in the future
		State stalePending = INIT_STATE.withProtocolGlobals(0, upgrades(TS));
		assertThrows(AssertionFailedError.class, () -> StateTest.doProtocolInvariantTests(stalePending));

		// Applied activation in the future
		State futureApplied = INIT_STATE.withProtocolGlobals(1, upgrades(TS + 1000));
		assertThrows(AssertionFailedError.class, () -> StateTest.doProtocolInvariantTests(futureApplied));
	}
}
