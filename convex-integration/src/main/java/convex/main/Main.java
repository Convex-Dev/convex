package convex.main;

import convex.gui.utils.Terminal;

public class Main {
	
	public static void main(String... args) {
		boolean terminal=Terminal.checkIfTerminal();
		if (terminal) {
			convex.cli.Main.main(args);
		} else {
			convex.gui.MainGUI.main(args);
		}
	}

}
