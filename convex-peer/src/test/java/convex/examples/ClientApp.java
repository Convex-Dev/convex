package convex.examples;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

import convex.api.Convex;
import convex.core.lang.RT;
import convex.peer.Server;

public class ClientApp {

	public static void main(String... args) throws IOException, InterruptedException, TimeoutException, ExecutionException {
		InetSocketAddress hostAddress = new InetSocketAddress("localhost", Server.DEFAULT_PORT);

		Convex convex = Convex.connect(hostAddress, null,null);

		// send a couple of queries, wait for results
		convex.querySync(RT.cvm("A beautiful life - something special - a magic moment"));
		convex.querySync(RT.cvm(1L));
		convex.close();

		System.exit(0);
	}

}
