package convex.cli.client;

import java.io.Console;
import java.util.List;

import convex.api.Convex;
import convex.api.ConvexDirect;
import convex.cli.Helpers;
import convex.core.Result;
import convex.core.crypto.AKeyPair;
import convex.core.cvm.Address;
import convex.core.cvm.State;
import convex.core.cvm.Symbols;
import convex.core.data.ACell;
import convex.core.data.AList;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.init.Init;
import convex.core.lang.RT;
import convex.core.lang.Reader;
import picocli.CommandLine.Option;

/**
 * Base class for commands that evaluate Convex Lisp source (eval, repl).
 *
 * Evaluation runs against whatever Convex instance is targeted: a peer
 * specified with the standard client options (--host / --port), or an
 * ephemeral local in-memory instance if no peer is specified.
 */
public abstract class AEvalCommand extends AClientCommand {

	@Option(names={"--query"},
			description="Evaluate forms as queries (read-only). No key pair is required, but state changes such as defs are discarded.")
	protected boolean queryMode;

	@Option(names={"--protocol-version"},
			description="Protocol version for the ephemeral local instance genesis. Default: latest version supported by this release. Ignored when connecting to a peer.")
	protected Long protocolVersion;

	/**
	 * Checks whether evaluation targets an ephemeral local instance (no peer specified)
	 * @return true if evaluating locally
	 */
	protected boolean isLocalTarget() {
		return peerMixin.getSpecifiedSource()==null;
	}

	/**
	 * Connects to the targeted Convex instance: the peer specified with --host / --port,
	 * or an ephemeral local in-memory instance if no peer is specified.
	 * @return Convex connection instance
	 */
	protected Convex connectEval() throws InterruptedException {
		if (isLocalTarget()) return createLocalInstance();
		Convex convex=connectQuery();
		if (!queryMode) ensureKeyPair(convex);
		return convex;
	}

	/**
	 * Creates an ephemeral local in-memory Convex instance with a freshly generated
	 * genesis. Transactions are signed with the generated genesis key, so no key
	 * store or account setup is required.
	 * @return Convex instance for local evaluation
	 */
	protected Convex createLocalInstance() {
		AKeyPair kp=AKeyPair.generate();
		State genesis=Helpers.applyGenesisProtocol(Init.createState(List.of(kp.getAccountKey())),protocolVersion);
		ConvexDirect convex=ConvexDirect.create(kp,genesis);
		Address a=addressMixin.getSpecifiedAddress();
		if (a!=null) convex.setAddress(a);
		return convex;
	}

	/**
	 * Reads source as a single evaluation unit. Multiple forms are wrapped in a
	 * `do` block, consistent with the GUI REPL.
	 * @param source Convex Lisp source
	 * @return Form to evaluate, or null if the source contains no forms
	 */
	protected ACell readForm(String source) {
		AList<ACell> forms=Reader.readAll(source);
		long n=forms.count();
		if (n==0) return null;
		return (n==1)?forms.get(0):forms.cons(Symbols.DO);
	}

	/**
	 * Evaluates a form as a transaction, or as a query in --query mode
	 * @param convex Convex instance to evaluate on
	 * @param form Form to evaluate
	 * @return Result of evaluation
	 */
	protected Result evalForm(Convex convex, ACell form) throws InterruptedException {
		return queryMode?convex.querySync(form):convex.transactSync(form);
	}

	/**
	 * Prints the result value in readable CVX format
	 * @param r Result to print
	 */
	protected void printResultValue(Result r) {
		println(printValue(r.getValue()));
	}

	/**
	 * Formats an error Result for display, including any error trace
	 * @param r Error result
	 * @return Formatted error message
	 */
	protected String formatError(Result r) {
		StringBuilder sb=new StringBuilder();
		sb.append("Error ");
		sb.append(r.getErrorCode());
		ACell v=r.getValue();
		if (v!=null) {
			sb.append(' ');
			sb.append(printValue(v));
		}
		AVector<AString> trace=r.getTrace();
		if (trace!=null) {
			for (AString t: trace) {
				sb.append("\n  ");
				sb.append(t.toString());
			}
		}
		return sb.toString();
	}

	private static String printValue(ACell v) {
		AString s=RT.print(v);
		return (s==null)?"<print limit exceeded>":s.toString();
	}

	/**
	 * Checks whether an interactive terminal console is attached. Unlike a bare
	 * System.console() null check, this stays false when standard input or output
	 * is redirected on Java 22+, where System.console() is non-null even for
	 * redirected streams (isTerminal() is reflected because it does not exist on
	 * the Java 21 compile target).
	 * @return true if an interactive terminal is attached
	 */
	protected static boolean isTerminalConsole() {
		Console c=System.console();
		if (c==null) return false;
		try {
			Object r=Console.class.getMethod("isTerminal").invoke(c);
			return Boolean.TRUE.equals(r);
		} catch (ReflectiveOperationException e) {
			// Java 21: no isTerminal(), but a non-null console implies a terminal
			return true;
		}
	}
}
