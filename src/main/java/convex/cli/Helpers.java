package convex.cli;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Random;

import convex.api.Convex;
import convex.cli.peer.Session;
import convex.core.crypto.AKeyPair;
import convex.core.data.Address;

/*
 *
 * Helpers
 *
 */
public class Helpers {

	public static String expandTilde(String path) {
		if (path!=null) {
			return path.replaceFirst("^~", System.getProperty("user.home"));
		}
		return null;
	}

	public static void createPath(File file) {
		File path = file.getParentFile();
		if (!path.exists()) {
			path.mkdir();
		}
	}
	public static Convex connect(String hostname, int port, Address address, AKeyPair keyPair) {
		InetSocketAddress host=new InetSocketAddress(hostname, port);
		System.out.printf("Connecting to peer: %s\n", host);
		Convex convex;
		try {
			convex=Convex.connect(host, address, keyPair);
		} catch (IOException e) {
			e.printStackTrace();
			System.out.printf("Failed to connect to peer at: %s\n", host);
			return null;
		}
		return convex;
	}

	public static int getSessionPort(String sessionFilename) throws IOException {
		int port = 0;
		Session session = new Session();
		Random random = new Random();
		File sessionFile = new File(sessionFilename);
		session.load(sessionFile);
		if (session.size() > 0) {
			port = session.getPort(random.nextInt(session.size() - 1));
		}
		return port;
	}
}


