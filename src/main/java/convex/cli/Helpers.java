package convex.cli;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;

import convex.api.Convex;
import convex.core.Init;

/*
 *
 * Helpers
 *
*/
public class Helpers {

	public static String expandTilde(String path) {
        return path.replaceFirst("^~", System.getProperty("user.home"));
	}

	public static void createPath(File file) {
		File path = file.getParentFile();
		if (!path.exists()) {
			path.mkdir();
		}
	}

	public static Convex connect(String hostname, int port) {
		InetSocketAddress host=new InetSocketAddress(hostname, port);
		System.out.printf("Connecting to peer: %s\n", host);
		Convex convex;
		try {
			convex=Convex.connect(host, Init.HERO, null); // TODO: what should keypair be?
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
			System.out.printf("Failed to connect to peer at: %s\n", host);
			return null;
		}
		return convex;
	}
}


