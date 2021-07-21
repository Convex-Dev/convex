package convex.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.PrintWriter;
import java.io.StringWriter;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import picocli.CommandLine;

public class Helper {


	public static void assertExecuteCommandLineResult(int returnCode, String patternText, String ... args) {
		CommandLineTester tester =  new CommandLineTester(args);
		assertEquals(returnCode, tester.getResult());
		tester.assertOutputMatch(patternText);
	}

	public static void assertCommandLineResult(int returnCode, String patternText, CommandLineTester tester) {
		assertEquals(returnCode, tester.getResult());
		tester.assertOutputMatch(patternText);
	}


}

