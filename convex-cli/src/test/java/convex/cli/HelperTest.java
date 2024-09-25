package convex.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

public class HelperTest {

	@Test 
	public void testSplitArray() {
		assertEquals(List.of("a","b","c"),Helpers.splitArrayParameter("a,b","c"));
		assertEquals(List.of(),Helpers.splitArrayParameter());
		assertEquals(List.of("a"),Helpers.splitArrayParameter(" a "));
	}
	
	public static void assertExecuteCommandLineResult(int exitCode, String patternText, String ... args) {
		CLTester tester =  CLTester.run(args);
		tester.assertExitCode(exitCode);;
		
		String output=tester.getOutput();
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
		Pattern regex = Pattern.compile(patternText, Pattern.MULTILINE + Pattern.DOTALL);
		Matcher matcher = regex.matcher(output);

		String assertText = "\nCommand: convex " + String.join(" ", tester.getArgs()) +
			"\nMatch: '" + patternText + "'" +
			"\nOutput: '" + output.substring(0, Math.min(132, output.length())) + "'" +
			"\n";
		assertTrue(matcher.find(),  assertText);
	}
}

