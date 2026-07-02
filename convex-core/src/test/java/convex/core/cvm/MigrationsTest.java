package convex.core.cvm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.Test;

import convex.core.cvm.Migrations.Migration;
import convex.core.init.InitTest;

/**
 * Tests for the migration registry. See UPGRADE.md.
 */
public class MigrationsTest {

	@Test
	public void testEmptyRegistry() {
		// No migrations released yet: nothing before the bootstrap ships
		assertEquals(0L, Migrations.MAX_VERSION);
		assertNull(Migrations.get(0));
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
	public void testIdentityMigration() {
		// The Migration interface is usable as a lambda; identity leaves state untouched
		Migration identity = s -> s;
		assertSame(InitTest.STATE, identity.apply(InitTest.STATE));
	}
}
