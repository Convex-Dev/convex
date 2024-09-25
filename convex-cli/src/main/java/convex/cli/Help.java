package convex.cli;

import java.io.PrintWriter;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Help.ColorScheme;
import picocli.CommandLine.IHelpCommandInitializable2;
import picocli.CommandLine.ParentCommand;

@Command(
		name="help",
		description="Show usage help and exit.",
		helpCommand = true)
public class Help extends ACommand implements IHelpCommandInitializable2  {

	@ParentCommand
	protected ACommand parent;
	protected CommandLine commandLine=null;

	@Override
	public void init(CommandLine helpCommandLine, ColorScheme colorScheme, PrintWriter outWriter,
			PrintWriter errWriter) {
		this.commandLine=helpCommandLine;
	}

	@Override
	public Main cli() {
		return parent.cli();
	}

	@Override
	protected void execute() throws InterruptedException {
		parent.showUsage();
	}
}
