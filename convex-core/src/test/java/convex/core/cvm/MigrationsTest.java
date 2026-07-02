package convex.core.cvm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.Test;

import convex.core.cvm.Migrations.Migration;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;
import convex.core.init.InitTest;

/**
 * Tests for the migration registry. See UPGRADE.md.
 */
public class MigrationsTest {

	@Test
	public void testRegistry() {
		// This release carries the v1 bootstrap migration only
		assertEquals(1L, Migrations.MAX_VERSION);
		assertInstanceOf(Migrations.Bootstrap.class, Migrations.get(0));
		assertNull(Migrations.get(1));
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

		// No protocol globals at all (genesis): nothing pending
		assertNull(Migrations.pendingBeyondSupport(init));

		// Only the supported bootstrap scheduled (count == MAX_VERSION == 1): no warning
		State v1only = init.withProtocolGlobals(0, Vectors.of(CVMLong.create(ts + 1000)));
		assertNull(Migrations.pendingBeyondSupport(v1only));

		// A version-2 upgrade pending beyond support (count 2 > MAX_VERSION 1)
		State v2pending = init.withProtocolGlobals(1,
				Vectors.of(CVMLong.create(ts - 1000), CVMLong.create(ts + 5000)));
		Migrations.UpgradeWarning w = Migrations.pendingBeyondSupport(v2pending);
		assertNotNull(w);
		assertEquals(2L, w.version);
		assertEquals(ts + 5000, w.activation);

		// Several pending beyond support: report the earliest (index MAX_VERSION)
		State many = init.withProtocolGlobals(1, Vectors.of(
				CVMLong.create(ts - 1000), CVMLong.create(ts + 5000), CVMLong.create(ts + 9000)));
		Migrations.UpgradeWarning w2 = Migrations.pendingBeyondSupport(many);
		assertEquals(2L, w2.version);
		assertEquals(ts + 5000, w2.activation);
	}

	@Test
	public void testIdentityMigration() {
		// The Migration interface is usable as a lambda; identity leaves state untouched
		Migration identity = s -> s;
		assertSame(InitTest.STATE, identity.apply(InitTest.STATE));
	}
}
