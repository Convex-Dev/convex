package convex.db;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/**
 * Basic tests for Convex DB module setup.
 */
public class ConvexDBTest {

	@Test
	public void testModuleLoads() {
		// Verify core dependencies are available
		assertNotNull(convex.core.data.ACell.class);
		assertNotNull(convex.lattice.kv.KVDatabase.class);

		// Verify Calcite is available
		assertNotNull(org.apache.calcite.sql.SqlNode.class);
	}
}
