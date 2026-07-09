package convex.cli.desktop;

import convex.cli.ATopCommand;
import convex.gui.MainGUI;
import picocli.CommandLine.Command;

@Command(name="desktop",
mixinStandardHelpOptions=true,
description="Run the Convex Desktop GUI")
public class Desktop extends ATopCommand {

	@Override
	public void execute() {
		MainGUI gui = new MainGUI();
		gui.run();
		cli().notifyStartup();
		gui.waitForClose();
	}

}
