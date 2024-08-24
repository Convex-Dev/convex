package convex.main;

public class Main {
	
	public static void main(String... args) {
		if (System.console()!=null) {
			convex.cli.Main.main(args);
		} else {
			convex.gui.MainGUI.main(args);
		}
	}

}
