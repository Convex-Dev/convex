package convex.cli.output;

import picocli.CommandLine.Help.Ansi;

public class Coloured {

	public static String green(String text) {
		return Ansi.ON.string("@|fg(46) "+text+"|@");
	}
	
	public static String red(String text) {
		return Ansi.ON.string("@|fg(160) "+text+"|@");
	}

	public static String blue(String text) {
		return Ansi.ON.string("@|fg(87) "+text+"|@");
	}

	public static String yellow(String text) {
		return Ansi.ON.string("@|fg(226) "+text+"|@");
	}

	public static String orange(String text) {
		return Ansi.ON.string("@|fg(172) "+text+"|@");
	}
}
