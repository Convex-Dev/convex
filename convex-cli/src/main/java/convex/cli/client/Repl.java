package convex.cli.client;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;

import convex.api.Convex;
import convex.cli.CLIError;
import convex.cli.ExitCodes;
import convex.cli.output.Coloured;
import convex.core.Result;
import convex.core.data.ACell;
import convex.core.exceptions.ParseException;
import picocli.CommandLine.Command;

/**
 * Convex repl sub command: interactive Convex Lisp read-eval-print loop
 *
 *		convex.repl
 */
@Command(name="repl",
	mixinStandardHelpOptions=true,
	description="Run an interactive Convex Lisp REPL. Uses an ephemeral local instance unless a peer is targeted with --host.")
public class Repl extends AEvalCommand {

	@Override
	public void execute() throws InterruptedException {
		try (Convex convex=connectEval()) {
			String target=isLocalTarget()?"local ephemeral instance":("peer "+peerMixin.getHostname());
			inform("Convex REPL on "+target+" as "+convex.getAddress()+" in "+(queryMode?"query":"transact")+" mode");
			inform("Enter forms to evaluate. 'quit' or end of input (Ctrl-D, Ctrl-Z on Windows) exits.");
			runRepl(convex);
		}
	}

	private void runRepl(Convex convex) throws InterruptedException {
		boolean terminal=isTerminalConsole();
		BufferedReader br=new BufferedReader(new InputStreamReader(System.in));
		StringBuilder pending=new StringBuilder();
		while (true) {
			if (terminal) showPrompt(pending.length()>0);
			String line;
			try {
				line=br.readLine();
			} catch (IOException e) {
				throw new CLIError(ExitCodes.IOERR,"Unable to read standard input: "+e.getMessage(),e);
			}
			if (line==null) break; // end of input

			if (pending.length()==0) {
				String trimmed=line.trim();
				if (trimmed.isEmpty()) continue;
				if (trimmed.equals("quit")||trimmed.equals("exit")) break;
			}
			pending.append(line).append('\n');

			ACell form;
			try {
				form=readForm(pending.toString());
			} catch (ParseException e) {
				// Incomplete input: keep reading lines until the form closes.
				// A blank line while incomplete aborts and reports the parse error.
				if (isIncompleteInput(e)&&!line.isBlank()) continue;
				informError(e.getMessage());
				pending.setLength(0);
				continue;
			}
			pending.setLength(0);
			if (form==null) continue;

			Result r=evalForm(convex,form);
			if (r.isError()) {
				informError(formatError(r));
			} else {
				printResultValue(r);
			}
		}
	}

	/**
	 * Writes the REPL prompt to stderr, keeping stdout as pure result output
	 */
	private void showPrompt(boolean continuation) {
		String p=continuation?"   ..> ":"convex> ";
		if (isColoured()) p=Coloured.blue(p);
		PrintWriter err=commandLine().getErr();
		err.print(p);
		err.flush();
	}

	/**
	 * True if a parse failure indicates incomplete input, i.e. more lines may
	 * complete the form. Matches the end-of-input messages produced by the reader
	 * (see ConvexErrorListener).
	 */
	private static boolean isIncompleteInput(ParseException e) {
		String msg=e.getMessage();
		if (msg==null) return false;
		return msg.contains("unexpected end of input")||msg.contains("unterminated string");
	}
}
