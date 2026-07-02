package convex.core.cvm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.function.LongFunction;

import org.junit.jupiter.api.Test;

import convex.core.cpos.Block;
import convex.core.cvm.Migrations.Migration;
import convex.core.data.AVector;
import convex.core.data.SignedData;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;
import convex.core.exceptions.UpgradeError;
import convex.core.init.InitTest;

/**
 * Tests for network upgrade application: the watermark loop in
 * State.applyUpgrades, wired as the first step of block preparation.
 * See UPGRADE.md.
 */
public class ApplyUpgradesTest {

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

	/**
	 * Test migration source: migration for version k+1 encodes its order of
	 * application into the global fees: fees = fees * 10 + (k+1)
	 */
	static final LongFunction<Migration> ORDERED = k -> s ->
		s.withGlobalFees(CVMLong.create(s.getGlobalFees().longValue() * 10 + (k + 1)));

	/** Migration source with no migrations available */
	static final LongFunction<Migration> NONE = k -> null;

	/** Migration source whose migrations always throw */
	static final LongFunction<Migration> BROKEN = k -> s -> {
		throw new IllegalStateException("boom");
	};

	@Test
	public void testNoOpWithoutUpgrades() {
		// No protocol globals at all (genesis case): identical state returned
		assertSame(INIT_STATE, INIT_STATE.applyUpgrades(TS + 1000000, ORDERED));
		assertSame(INIT_STATE, INIT_STATE.applyUpgrades(TS + 1000000)); // default registry path

		// Empty upgrade vector: no-op
		State s = INIT_STATE.withProtocolGlobals(0, upgrades());
		assertSame(s, s.applyUpgrades(TS + 1000000, ORDERED));
	}

	@Test
	public void testBoundary() {
		long activation = TS + 1000;
		State pending = INIT_STATE.withProtocolGlobals(0, upgrades(activation));

		// The instant before activation: nothing fires
		assertSame(pending, pending.applyUpgrades(activation - 1, ORDERED));

		// Exactly at activation: fires
		State fired = pending.applyUpgrades(activation, ORDERED);
		assertEquals(1L, fired.getProtocolVersion());
		assertSame(pending.getUpgradeVector(), fired.getUpgradeVector()); // vector untouched
		assertEquals(1L, fired.getGlobalFees().longValue()); // migration effect present

		// Watermark never revisits: reapplying at a later time is a no-op
		assertSame(fired, fired.applyUpgrades(activation + 1000, ORDERED));

		// Committed-state invariants hold once time advances to the block timestamp
		StateTest.doStateTests(fired.applyTimeUpdate(activation));
	}

	@Test
	public void testCatchUp() {
		// Three pending upgrades, two sharing an activation timestamp
		long t1 = TS + 1000;
		long t2 = TS + 2000;
		State pending = INIT_STATE.withProtocolGlobals(0, upgrades(t1, t1, t2));

		// A single block past all activations fires all three, in order
		State fired = pending.applyUpgrades(t2, ORDERED);
		assertEquals(3L, fired.getProtocolVersion());
		assertEquals(123L, fired.getGlobalFees().longValue()); // order 1,2,3 encoded

		StateTest.doStateTests(fired.applyTimeUpdate(t2));
	}

	@Test
	public void testPartialCatchUp() {
		long t1 = TS + 1000;
		long t2 = TS + 2000;
		State pending = INIT_STATE.withProtocolGlobals(0, upgrades(t1, t2));

		// Block between the activations fires only the first
		State fired = pending.applyUpgrades(t1, ORDERED);
		assertEquals(1L, fired.getProtocolVersion());
		assertEquals(1L, fired.getGlobalFees().longValue());

		// Second remains pending, fires later
		State fired2 = fired.applyUpgrades(t2, ORDERED);
		assertEquals(2L, fired2.getProtocolVersion());
		assertEquals(12L, fired2.getGlobalFees().longValue());

		StateTest.doStateTests(fired.applyTimeUpdate(t1));
		StateTest.doStateTests(fired2.applyTimeUpdate(t2));
	}

	@Test
	public void testDeterminism() {
		long t1 = TS + 1000;
		State pending = INIT_STATE.withProtocolGlobals(0, upgrades(t1, t1));

		State a = pending.applyUpgrades(t1, ORDERED);
		State b = pending.applyUpgrades(t1, ORDERED);
		assertEquals(a.getHash(), b.getHash());
	}

	@Test
	public void testMissingMigration() {
		long activation = TS + 1000;
		State pending = INIT_STATE.withProtocolGlobals(0, upgrades(activation));

		// Not yet due: no error even with no migrations available
		assertSame(pending, pending.applyUpgrades(activation - 1, NONE));

		// Due but unavailable: UpgradeError identifying the version
		UpgradeError e = assertThrows(UpgradeError.class, () -> pending.applyUpgrades(activation, NONE));
		assertEquals(1L, e.getVersion());

		// Default registry is empty pre-bootstrap, so behaves the same
		assertThrows(UpgradeError.class, () -> pending.applyUpgrades(activation));
	}

	@Test
	public void testFailingMigration() {
		long activation = TS + 1000;
		State pending = INIT_STATE.withProtocolGlobals(0, upgrades(activation));

		UpgradeError e = assertThrows(UpgradeError.class, () -> pending.applyUpgrades(activation, BROKEN));
		assertEquals(1L, e.getVersion());
		assertInstanceOf(IllegalStateException.class, e.getCause());
	}

	@Test
	public void testErrorPropagatesThroughApplyBlock() {
		// The critical replay-consistency property: a due-but-unavailable upgrade
		// must NOT become an invalid-block result via applyBlock's catch(Exception).
		// It must propagate as an Error for the peer layer to handle (withdraw).
		long activation = TS + 1000;
		State pending = INIT_STATE.withProtocolGlobals(0, upgrades(activation));

		Block b = Block.of(activation); // empty block at the activation boundary
		SignedData<Block> sb = InitTest.FIRST_PEER_KEYPAIR.signData(b);

		// Registry is empty pre-bootstrap: missing migration
		UpgradeError e = assertThrows(UpgradeError.class, () -> pending.applyBlock(sb));
		assertEquals(1L, e.getVersion());

		// Before activation the same block machinery applies cleanly
		Block early = Block.of(activation - 1);
		SignedData<Block> sbEarly = InitTest.FIRST_PEER_KEYPAIR.signData(early);
		State after = pending.applyBlock(sbEarly).getState();
		assertEquals(0L, after.getProtocolVersion());
		StateTest.doStateTests(after);
	}

	@Test
	public void testScheduledBeyondSupportedVersion() {
		// A node supporting version 2 sees version 3 scheduled: it must operate
		// normally while the entry is pending, and stop exactly at the transition
		// block — which by definition it cannot run
		long activation = TS + 5000;
		State atV2 = INIT_STATE.withProtocolGlobals(2, upgrades(TS - 2000, TS - 1000, activation));

		// This release only carries migrations up to version 2
		LongFunction<Migration> supportsTwo = k -> (k < 2) ? ORDERED.apply(k) : null;

		// Normal operation before activation: upgrades machinery is a no-op
		assertSame(atV2, atV2.applyUpgrades(activation - 1, supportsTwo));

		// Full blocks before activation apply cleanly, pending entry untouched
		Block early = Block.of(activation - 1);
		State after = atV2.applyBlock(InitTest.FIRST_PEER_KEYPAIR.signData(early)).getState();
		assertEquals(2L, after.getProtocolVersion());
		assertEquals(3L, after.getUpgradeVector().count());
		StateTest.doStateTests(after);

		// The transition block cannot be run: withdraw, identifying version 3
		UpgradeError e = assertThrows(UpgradeError.class,
				() -> after.applyUpgrades(activation, supportsTwo));
		assertEquals(3L, e.getVersion());

		// A release carrying version 3 applies the same transition block fine
		LongFunction<Migration> supportsThree = k -> (k < 3) ? ORDERED.apply(k) : null;
		State fired = after.applyUpgrades(activation, supportsThree);
		assertEquals(3L, fired.getProtocolVersion());
	}

	@Test
	public void testMalformedUpgradeVector() {
		// A malformed vector entry (only producible by a rogue migration) must fail
		// as UpgradeError, never as an Exception that becomes an invalid block
		@SuppressWarnings({"unchecked", "rawtypes"})
		AVector<CVMLong> junk = (AVector) Vectors.of(convex.core.data.Strings.create("oops"));
		State bad = INIT_STATE.withProtocolGlobals(0, junk);

		UpgradeError e = assertThrows(UpgradeError.class, () -> bad.applyUpgrades(TS + 1000, ORDERED));
		assertInstanceOf(ClassCastException.class, e.getCause());

		// Same guarantee through the full block application path
		Block b = Block.of(TS + 1000);
		SignedData<Block> sb = InitTest.FIRST_PEER_KEYPAIR.signData(b);
		assertThrows(UpgradeError.class, () -> bad.applyBlock(sb));
	}

	@Test
	public void testEnvironmentalFailure() {
		// Peer-local conditions (e.g. missing store data) also resolve to withdraw,
		// with the cause preserved so the peer layer can report accurately: this is
		// a resync-and-retry condition, not a release update
		long activation = TS + 1000;
		State pending = INIT_STATE.withProtocolGlobals(0, upgrades(activation));

		LongFunction<Migration> missingData = k -> s -> {
			throw new convex.core.exceptions.MissingDataException(null, s.getHash());
		};

		UpgradeError e = assertThrows(UpgradeError.class, () -> pending.applyUpgrades(activation, missingData));
		assertEquals(1L, e.getVersion());
		assertInstanceOf(convex.core.exceptions.MissingDataException.class, e.getCause());
	}

	@Test
	public void testMigrationMayEditUpgradeVector() {
		// A migration may legitimately modify the protocol globals itself (the
		// format can be evolved by an upgrade); the loop re-reads each iteration
		long t1 = TS + 1000;
		long t2 = TS + 2000;
		State pending = INIT_STATE.withProtocolGlobals(0, upgrades(t1));

		// Migration for v1 schedules a further upgrade at t2
		LongFunction<Migration> scheduling = k -> (k == 0)
				? s -> s.withProtocolGlobals(s.getProtocolVersion(), upgrades(t1, t2))
				: s -> s.withGlobalFees(CVMLong.create(77));

		// At t1: v1 fires and extends the vector; t2 not yet due
		State fired = pending.applyUpgrades(t1, scheduling);
		assertEquals(1L, fired.getProtocolVersion());
		assertEquals(2L, fired.getUpgradeVector().count());

		// At t2: the migration-scheduled upgrade fires
		State fired2 = fired.applyUpgrades(t2, scheduling);
		assertEquals(2L, fired2.getProtocolVersion());
		assertEquals(77L, fired2.getGlobalFees().longValue());
	}
}
