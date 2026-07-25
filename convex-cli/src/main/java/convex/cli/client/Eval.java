package convex.cli.client;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import convex.api.Convex;
import convex.cli.CLIError;
import convex.cli.ExitCodes;
import convex.core.Result;
import convex.core.data.ACell;
import convex.core.exceptions.ParseException;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

/**
 * Convex eval sub command: headless, scriptable evaluation of Convex Lisp
 *
 *		convex.eval
 */
@Command(name="eval",
	mixinStandardHelpOptions=true,
	description="Evaluate Convex Lisp forms and print the results. Uses an ephemeral local instance unless a peer is targeted with --host. Reads from standard input if no forms are given, or where a form argument is '-'.")
public class Eval extends AEvalCommand {

	@Parameters(paramLabel="forms",
		description="Convex Lisp source to evaluate. Each argument is evaluated in sequence as a separate transaction (or query with --query) and its result printed.")
	private String[] forms;

	private String stdinSource=null;

	@Override
	public void execute() throws InterruptedException {
		List<String> units=new ArrayList<>();
		if ((forms==null)||(forms.length==0)) {
			if (isTerminalConsole()) {
				// Interactive terminal with no forms given: show usage rather than blocking on stdin
				showUsage();
				return;
			}
			units.add(readStdin());
		} else {
			for (String s: forms) {
				units.add("-".equals(s)?readStdin():s);
			}
		}

		try (Convex convex=connectEval()) {
			for (String src: units) {
				ACell form;
				try {
					form=readForm(src);
				} catch (ParseException e) {
					throw new CLIError(ExitCodes.DATAERR,e.getMessage());
				}
				if (form==null) continue;
				Result r=evalForm(convex,form);
				if (r.isError()) {
					throw new CLIError(formatError(r));
				}
				printResultValue(r);
			}
		}
	}

	/**
	 * Reads standard input in full. Reads at most once: subsequent calls return
	 * the empty string.
	 */
	private String readStdin() {
		if (stdinSource!=null) return "";
		try {
			stdinSource=new String(System.in.readAllBytes(),StandardCharsets.UTF_8);
			return stdinSource;
		} catch (IOException e) {
			throw new CLIError(ExitCodes.IOERR,"Unable to read standard input: "+e.getMessage(),e);
		}
	}
}
