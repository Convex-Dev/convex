package convex.main;

import convex.gui.utils.Toolkit;

public class Main {
	
	public static void main(String... args) {
		boolean terminal=Toolkit.checkIfTerminal();
		if (terminal) {
			convex.cli.Main.main(args);
		} else {
			convex.gui.MainGUI.main(args);
		}
	}

}
