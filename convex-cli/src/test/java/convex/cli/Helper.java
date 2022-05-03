package convex.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Helper {


	public static void assertExecuteCommandLineResult(int returnCode, String patternText, String ... args) {
		CLTester tester =  CLTester.run(args);
		assertEquals(returnCode, tester.getResult());
		
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

