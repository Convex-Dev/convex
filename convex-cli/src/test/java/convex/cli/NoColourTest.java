package convex.cli;

import static convex.cli.HelperTest.assertExecuteCommandLineResult;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Tests for NO_COLOR handling.
 *
 * <p>The convention (<a href="https://no-color.org/">no-color.org</a>) is that colour is
 * suppressed when the variable is present and not empty, <em>regardless of its value</em>.
 * Binding it to the {@code --no-color} boolean option instead made picocli parse the
 * value, so a common {@code NO_COLOR=1} made every command exit 64 with
 * "'1' is not a boolean" — the CLI would not run at all.
 */
public class NoColourTest {

	@Test
	public void testNonEmptyValueSuppressesColour() {
		// Any non-empty value, not just boolean-looking ones
		assertTrue(Main.suppressesColour("1"));
		assertTrue(Main.suppressesColour("true"));
		assertTrue(Main.suppressesColour("false"), "Value is not interpreted, only presence");
		assertTrue(Main.suppressesColour("0"), "Value is not interpreted, only presence");
		assertTrue(Main.suppressesColour("yes"));
		assertTrue(Main.suppressesColour(" "));
	}

	@Test
	public void testUnsetOrEmptyLeavesColourEnabled() {
		assertFalse(Main.suppressesColour(null), "Unset means no opinion");
		assertFalse(Main.suppressesColour(""), "Empty string explicitly does not suppress");
	}

	/**
	 * The regression that matters: a value that is not a valid boolean must not stop the
	 * CLI parsing its arguments. Previously this exited 64 before running anything.
	 */
	@Test
	public void testNonBooleanValueDoesNotBreakArgumentParsing() {
		assertExecuteCommandLineResult(0, "^Usage: convex ", "--help");
		assertExecuteCommandLineResult(0, "^Usage: convex account ", "account", "help");
	}

	/** The explicit flag still works, and is still accepted alongside other options. */
	@Test
	public void testExplicitFlagStillAccepted() {
		assertExecuteCommandLineResult(0, "^Usage: convex ", "--no-color", "--help");
		assertExecuteCommandLineResult(0, "^Usage: convex key ", "--no-color", "key", "help");
	}
}
