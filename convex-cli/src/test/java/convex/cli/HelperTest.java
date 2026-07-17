package convex.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import convex.core.crypto.AKeyPair;
import convex.core.cvm.Migrations;
import convex.core.cvm.State;
import convex.core.init.Init;

public class HelperTest {

	@Test
	public void testSplitArray() {
		assertEquals(List.of("a","b","c"),Helpers.splitArrayParameter("a,b","c"));
		assertEquals(List.of(),Helpers.splitArrayParameter());
		assertEquals(List.of("a"),Helpers.splitArrayParameter(" a "));
	}

	@Test
	public void testApplyGenesisProtocol() {
		State genesis=Init.createState(List.of(AKeyPair.generate().getAccountKey()));
		// Default: latest supported protocol version
		assertEquals(Migrations.MAX_VERSION,Helpers.applyGenesisProtocol(genesis,null).getProtocolVersion());
		// Pinned to 0: the raw genesis, unchanged
		assertSame(genesis,Helpers.applyGenesisProtocol(genesis,0L));
		// Out-of-range pins are usage errors
		assertThrows(CLIError.class,()->Helpers.applyGenesisProtocol(genesis,-1L));
		assertThrows(CLIError.class,()->Helpers.applyGenesisProtocol(genesis,Migrations.MAX_VERSION+1));
	}
	
	public static void assertExecuteCommandLineResult(int exitCode, String patternText, String ... args) {
		CLTester tester =  CLTester.run(args);
		tester.assertExitCode(exitCode);;

		String output=tester.getOutput();
		// Strip ANSI escape codes for reliable pattern matching
		output = output.replaceAll("\u001B\\[[;\\d]*m", "");
		Pattern regex = Pattern.compile(patternText, Pattern.MULTILINE + Pattern.DOTALL);
		Matcher matcher = regex.matcher(output);

		String assertText = "\nCommand: convex " + String.join(" ", args) +
			"\nMatch: '" + patternText + "'" +
			"\nOutput: '" + output.substring(0, Math.min(132, output.length())) + "'" +
			"\n";
		assertTrue(matcher.find(),  assertText);

	}

	public static void assertCommandLineResult(int returnCode, String patternText, CLTester tester) {
		String output=tester.getOutput();
		// Strip ANSI escape codes for reliable pattern matching
		output = output.replaceAll("\u001B\\[[;\\d]*m", "");
		Pattern regex = Pattern.compile(patternText, Pattern.MULTILINE + Pattern.DOTALL);
		Matcher matcher = regex.matcher(output);

		String assertText = "\nCommand: convex " + String.join(" ", tester.getArgs()) +
			"\nMatch: '" + patternText + "'" +
			"\nOutput: '" + output.substring(0, Math.min(132, output.length())) + "'" +
			"\n";
		assertTrue(matcher.find(),  assertText);
	}
}

