package convex.cli;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import convex.cli.peer.Session;
import convex.cli.peer.SessionItem;

/**
 *
 * Helpers
 *
 *	Helper functions for the CLI classes.
 *
*/
public class Helpers {

	/**
	 * Expand a path string with a '~'. The tilde is expanded to the users home path.
	 *
	 * @param path Path string to expand.
	 *
	 * @return Expanded string if a tilde is present.
	 *
	 */
	public static String expandTilde(String path) {
		if (path!=null) {
			return path.replaceFirst("^~", System.getProperty("user.home"));
		}
		return null;
	}

	/**
	 * Create a path from a File object. This is to provide a feature to add the
	 * default `.convex` folder if it does not exist.
	 *
	 * @param file File object to see if the path part of the filename exists, if not then create it.
	 *
	 */
	public static void createPath(File file) {
		File path = file.getParentFile();
		if (!path.exists()) {
			path.mkdir();
		}
	}

	/**
	 * Return a random session hostname, by looking at the session file.
	 * The session file has a list of local peers open.
	 * This helper will find a random peer in the collection and returns hostname.
	 *
	 * @param sessionFilename Session filename to open and get the random port nummber.
	 *
	 * @return A random hostname or null if none can be found
	 * @throws IOException
	 *
	 */
	public static SessionItem getSessionItem(String sessionFilename) throws IOException {
		return getSessionItem(sessionFilename, -1);
	}

	/**
	 * Return an indexed session item, by looking at the session file.
	 * The session file has a list of local peers open.
	 * This helper will find a random peer in the collection and returns session item.
	 *
	 * @param sessionFilename Session filename to open and get the random port nummber.
	 *
	 * @param index The index of the peer in the session list or if -1 a random selection is made.
	 *
	 * @return A random session item or null if none can be found
	 * @throws IOException
	 *
	 */
	public static SessionItem getSessionItem(String sessionFilename, int index) throws IOException {
		SessionItem item = null;
        Session session = new Session();
		Random random = new Random();
		File sessionFile = new File(sessionFilename);
		session.load(sessionFile);
		int sessionCount = session.getSize();
		if (sessionCount > 0) {
			if (index < 0) {
				index = random.nextInt(sessionCount - 1);
			}
			item = session.getItemFromIndex(index);
		}
		return item;
	}

	public static List<String> splitArrayParameter(String[] parameterValue) {
		List<String> result = new ArrayList<>(parameterValue.length);
		for (int index = 0; index < parameterValue.length; index ++) {
			String value = parameterValue[index];
			String[] items = new String[1];
			items[0] = value;
			if (value.indexOf(",") > 0) {
				items = value.split(",");
			}
			for (int itemIndex = 0; itemIndex < items.length; itemIndex ++ ) {
				String newValue = items[itemIndex].trim();
				if (newValue.length() > 0) {
					result.add(newValue);
				}
			}
		}
		return result;
	}
}


