package convex.node;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.module.ModuleDescriptor;

import org.junit.jupiter.api.Test;

/** Verifies the supported modular API boundary for lattice-node composition. */
public class NodeModuleTest {

	@Test
	public void testNodePackageIsExported() {
		ModuleDescriptor descriptor=NodeServer.class.getModule().getDescriptor();
		assertNotNull(descriptor,"convex-peer tests should execute as a named module");
		assertTrue(descriptor.exports().stream()
			.anyMatch(export -> "convex.node".equals(export.source())
				&& !export.isQualified()),
			"convex.node must be exported to all module consumers");
	}
}
