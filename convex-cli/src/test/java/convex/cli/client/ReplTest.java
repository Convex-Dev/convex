package convex.cli.client;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import convex.cli.CLTester;
import convex.cli.ExitCodes;

/**
 * Tests for the `convex repl` command, driven via piped standard input
 * against an ephemeral local instance
 */
public class ReplTest {

	@Test
	public void testReplSession() {
		CLTester tester=CLTester.runWithInput("(+ 1 2)\n(def a 7)\n(* a 6)\n","repl");
		tester.assertExitCode(ExitCodes.SUCCESS);
		assertArrayEquals(new String[] {"3","7","42"},tester.getOutput().trim().split("\\R"));
	}

	@Test
	public void testMultiLineForm() {
		CLTester tester=CLTester.runWithInput("(+ 1\n2)\n","repl");
		tester.assertExitCode(ExitCodes.SUCCESS);
		assertEquals("3",tester.getOutput().trim());
	}

	@Test
	public void testQuitEndsSession() {
		CLTester tester=CLTester.runWithInput("quit\n(+ 1 2)\n","repl");
		tester.assertExitCode(ExitCodes.SUCCESS);
		assertEquals("",tester.getOutput().trim());
	}

	@Test
	public void testErrorContinuesSession() {
		CLTester tester=CLTester.runWithInput("(fail :FOO \"bad\")\n(+ 2 2)\n","repl");
		tester.assertExitCode(ExitCodes.SUCCESS);
		assertEquals("4",tester.getOutput().trim());
		assertTrue(tester.getError().contains(":FOO"),()->"Expected error code in: "+tester.getError());
	}

	@Test
	public void testParseErrorRecovery() {
		CLTester tester=CLTester.runWithInput("(]\n(+ 3 3)\n","repl");
		tester.assertExitCode(ExitCodes.SUCCESS);
		assertEquals("6",tester.getOutput().trim());
		assertTrue(tester.getError().contains("Parse error"),()->"Expected parse error in: "+tester.getError());
	}

	@Test
	public void testQueryMode() {
		CLTester tester=CLTester.runWithInput("(+ 40 2)\n","repl","--query");
		tester.assertExitCode(ExitCodes.SUCCESS);
		assertEquals("42",tester.getOutput().trim());
	}
}
