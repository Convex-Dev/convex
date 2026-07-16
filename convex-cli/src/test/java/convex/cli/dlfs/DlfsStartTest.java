package convex.cli.dlfs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import convex.cli.CLTester;
import convex.cli.ExitCodes;

public class DlfsStartTest {

	@Test
	public void testPersistentStoreRequiresStableKey() {
		CLTester result=CLTester.run("dlfs", "start", "--etch", "unused.etch");

		assertEquals(ExitCodes.CONFIG, result.getResult());
		assertTrue(result.getError().contains("requires a stable key"));
	}

	@Test
	public void testInvalidDriveRejectedBeforeStartup() {
		CLTester result=CLTester.run("dlfs", "start", "--drive", "../escape");

		assertEquals(ExitCodes.CONFIG, result.getResult());
		assertTrue(result.getError().contains("Invalid drive name"));
	}
}
