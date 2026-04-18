package convex.main;

import convex.gui.utils.Terminal;

/**
 * Implements the main entry point for convex.jar
 */
public class Main {
	
	/**
	 * Main entry point for convex.jar 
	 * 
	 * @param args Command line arguments
	 */
	public static void main(String... args) {
		// Any CLI args force CLI mode — the GUI takes no args, and routing them
		// to the GUI would silently drop them (e.g. `convex.jar --version`
		// would launch the GUI from a non-terminal shell like Git Bash).
		if (args.length > 0) {
			convex.cli.Main.main(args);
			return;
		}
		boolean terminal=Terminal.checkIfTerminal();
		if (terminal) {
			convex.cli.Main.main(args);
		} else {
			convex.gui.MainGUI.main(args);
		}
	}

}
