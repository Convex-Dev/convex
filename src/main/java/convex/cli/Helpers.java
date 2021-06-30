package convex.cli;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Random;
import java.util.concurrent.TimeoutException;

import convex.api.Convex;
import convex.cli.peer.Session;
import convex.cli.peer.SessionItem;
import convex.core.crypto.AKeyPair;
import convex.core.data.Address;
import convex.core.util.Utils;
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
	 * Connect to the convex network.
	 *
	 * @param hostname Hostname of the convex network.
	 *
	 * @param port Port of the network peer/connection.
	 *
	 * @param address Address of the account to connect as.
	 *
	 * @param keyPair Keypair to connect as.
	 *
	 * @return A valid convex connection object.
	 *
	 */
	public static Convex connect(String hostname, int port, Address address, AKeyPair keyPair) {
		InetSocketAddress host=new InetSocketAddress(hostname, port);
		System.out.printf("Connecting to peer: %s\n", host);
		Convex convex;
		try {
			convex=Convex.connect(host, address, keyPair);
		} catch (IOException | TimeoutException e) {
			e.printStackTrace();
			System.out.printf("Failed to connect to peer at: %s\n", host);
			return null;
		}
		return convex;
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
	public static String getSessionHostname(String sessionFilename) throws IOException {
		return getSessionHostname(sessionFilename, -1);
	}


	/**
	 * Return an indexed session hostname, by looking at the session file.
	 * The session file has a list of local peers open.
	 * This helper will find a random peer in the collection and returns hostname.
	 *
	 * @param sessionFilename Session filename to open and get the random port nummber.
	 *
	 * @param index The index of the peer in the session list or if -1 a random selection is made.
	 *
	 * @return A random hostname or null if none can be found
	 * @throws IOException
	 *
	 */
	public static String getSessionHostname(String sessionFilename, int index) throws IOException {
        String hostname = null;
		Session session = new Session();
		Random random = new Random();
		File sessionFile = new File(sessionFilename);
		session.load(sessionFile);
		int sessionCount = session.getSize();
		if (sessionCount > 0) {
			if (index < 0) {
				index = random.nextInt(sessionCount - 1);
			}
			SessionItem item = session.getItemFromIndex(index);
			if (item != null) {
				hostname = item.getHostname();
			}
		}
		return hostname;
	}

	/**
	 * Return a random session port from the local running network.
	 * @param sessionFilename
	 *
	 * @return Port number (may be zero)
	 * @throws IOException
	 */
	public static int getSessionPort(String sessionFilename) throws IOException {
		return getSessionPort(sessionFilename, -1);
	}
	/**
	 * Return a random or indexed session port from the local running network.
	 * @param sessionFilename
	 *
	 * @param index Index of the peer session to return, or if -1 return a random peer port number.
	 *
	 * @return Port number (may be zero)
	 * @throws IOException
	 */
	public static int getSessionPort(String sessionFilename, int index) throws IOException {
	String hostname = Helpers.getSessionHostname(sessionFilename, index);
	if ( hostname != null) {
		InetSocketAddress address = Utils.toInetSocketAddress(hostname);
		return address.getPort();
	}
	return 0;
	}

}


