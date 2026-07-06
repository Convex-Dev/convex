package convex.core.cvm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import convex.core.cvm.Migrations.Migration;
import convex.core.data.AVector;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;
import convex.core.init.InitTest;

/**
 * Tests for the migration registry. See UPGRADE.md.
 */
public class MigrationsTest {

	@Test
	public void testRegistry() {
		// v1 bootstrap is always the first migration
		assertTrue(Migrations.MAX_VERSION >= 1);
		assertInstanceOf(Migrations.Bootstrap.class, Migrations.get(0));
		// Every supported version has a migration; MAX_VERSION and beyond do not
		for (long k = 0; k < Migrations.MAX_VERSION; k++) assertNotNull(Migrations.get(k));
		assertNull(Migrations.get(Migrations.MAX_VERSION));
	}

	@Test
	public void testOutOfRange() {
		// A release carries migrations for versions 1..MAX_VERSION only
		assertNull(Migrations.get(-1));
		assertNull(Migrations.get(Migrations.MAX_VERSION));
		assertNull(Migrations.get(Long.MAX_VALUE));
		assertNull(Migrations.get(Integer.MAX_VALUE + 1L));
	}

	@Test
	public void testPendingBeyondSupport() {
		State init = InitTest.STATE;
		long ts = init.getTimestamp().longValue();
		long m = Migrations.MAX_VERSION;

		// No protocol globals at all (genesis): nothing pending
		assertNull(Migrations.pendingBeyondSupport(init));

		// Exactly MAX_VERSION upgrades, all applied (count == MAX_VERSION): all supported, no warning
		AVector<CVMLong> applied = Vectors.empty();
		for (long i = 0; i < m; i++) applied = applied.conj(CVMLong.create(ts - 1000));
		assertNull(Migrations.pendingBeyondSupport(init.withProtocolGlobals(m, applied)));

		// One more scheduled beyond support (count == MAX_VERSION + 1): warns about version M+1
		AVector<CVMLong> beyond = applied.conj(CVMLong.create(ts + 5000));
		Migrations.UpgradeWarning w = Migrations.pendingBeyondSupport(init.withProtocolGlobals(m, beyond));
		assertNotNull(w);
		assertEquals(m + 1, w.version);
		assertEquals(ts + 5000, w.activation);

		// Several beyond support: report the earliest (index MAX_VERSION)
		AVector<CVMLong> many = beyond.conj(CVMLong.create(ts + 9000));
		Migrations.UpgradeWarning w2 = Migrations.pendingBeyondSupport(init.withProtocolGlobals(m, many));
		assertEquals(m + 1, w2.version);
		assertEquals(ts + 5000, w2.activation);
	}

	@Test
	public void testIdentityMigration() {
		// The Migration interface is usable as a lambda; identity leaves state untouched
		Migration identity = s -> s;
		assertSame(InitTest.STATE, identity.apply(InitTest.STATE));
	}
}
