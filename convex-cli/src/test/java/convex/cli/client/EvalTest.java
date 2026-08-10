package convex.cli.client;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import convex.cli.CLTester;
import convex.cli.ExitCodes;

/**
 * Tests for the `convex eval` command against an ephemeral local instance
 */
public class EvalTest {

	@Test
	public void testSimpleEval() {
		CLTester tester=CLTester.run("eval","(+ 1 2)");
		tester.assertExitCode(ExitCodes.SUCCESS);
		assertEquals("3",tester.getOutput().trim());
	}

	@Test
	public void testStatePersistsAcrossForms() {
		CLTester tester=CLTester.run("eval","(def x 10)","(* x x)");
		tester.assertExitCode(ExitCodes.SUCCESS);
		assertArrayEquals(new String[] {"10","100"},tester.getOutput().trim().split("\\R"));
	}

	@Test
	public void testMultipleFormsInOneArgument() {
		// Multiple forms in one argument are wrapped in a do block: last value wins
		CLTester tester=CLTester.run("eval","(def a 2) (* a 21)");
		tester.assertExitCode(ExitCodes.SUCCESS);
		assertEquals("42",tester.getOutput().trim());
	}

	@Test
	public void testQueryMode() {
		CLTester tester=CLTester.run("eval","--query","(+ 2 3)");
		tester.assertExitCode(ExitCodes.SUCCESS);
		assertEquals("5",tester.getOutput().trim());
	}

	@Test
	public void testStringResultIsPrintedReadably() {
		CLTester tester=CLTester.run("eval","(str \"foo\" 1)");
		tester.assertExitCode(ExitCodes.SUCCESS);
		assertEquals("\"foo1\"",tester.getOutput().trim());
	}

	@Test
	public void testStdinEval() {
		CLTester tester=CLTester.runWithInput("(def a 3) (+ a a)\n","eval","-");
		tester.assertExitCode(ExitCodes.SUCCESS);
		assertEquals("6",tester.getOutput().trim());
	}

	@Test
	public void testErrorGivesNonZeroExit() {
		CLTester tester=CLTester.run("eval","(fail :FOO \"bad\")");
		tester.assertExitCode(ExitCodes.ERROR);
		assertTrue(tester.getError().contains(":FOO"),()->"Expected error code in: "+tester.getError());
	}

	@Test
	public void testParseErrorGivesDataErrExit() {
		CLTester tester=CLTester.run("eval","(+ 1");
		tester.assertExitCode(ExitCodes.DATAERR);
	}

	@Test
	public void testProtocolVersionZero() {
		CLTester tester=CLTester.run("eval","--protocol-version","0","(+ 1 2)");
		tester.assertExitCode(ExitCodes.SUCCESS);
		assertEquals("3",tester.getOutput().trim());
	}
}
