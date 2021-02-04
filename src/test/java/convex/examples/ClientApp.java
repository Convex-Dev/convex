package convex.examples;

import java.io.IOException;
import java.net.InetSocketAddress;

import convex.api.Convex;
import convex.core.lang.RT;
import convex.peer.Server;

public class ClientApp {

	public static void main(String... args) throws IOException, InterruptedException {
		InetSocketAddress hostAddress = new InetSocketAddress("localhost", Server.DEFAULT_PORT + 1);

		Convex pc = Convex.connect(hostAddress, null,null);

		// send a couple of queries, wait for results
		pc.query(RT.cvm("A beautiful life - something special - a magic moment"));
		pc.query(RT.cvm(1L));
		Thread.sleep(3000);
		pc.close();

		System.exit(0);
	}

}
