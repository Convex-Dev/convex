package convex.cli;

/*
 *
 * Helpers
 *
*/
public class Helpers {
	public static String expandTilde(String path) {
        return path.replaceFirst("^~", System.getProperty("user.home"));
	}
}
