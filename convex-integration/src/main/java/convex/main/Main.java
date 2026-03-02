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
		boolean terminal=Terminal.checkIfTerminal();
		if (terminal) {
			convex.cli.Main.main(args);
		} else {
			convex.gui.MainGUI.main(args);
		}
	}

}
