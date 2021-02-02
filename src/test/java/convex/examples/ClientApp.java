package convex.examples;

import java.io.IOException;
import java.net.InetSocketAddress;

import convex.core.data.Ref;
import convex.core.lang.RT;
import convex.core.store.Stores;
import convex.net.Connection;
import convex.peer.Server;

public class ClientApp {

	public static void main(String... args) throws IOException, InterruptedException {
		InetSocketAddress hostAddress = new InetSocketAddress("localhost", Server.DEFAULT_PORT + 1);

		Connection pc = Connection.connect(hostAddress, m -> {
			switch (m.getType()) {
			// we need to handle data messages if results might be nested
			case DATA:
				Ref.createPersisted(m.getPayload());
				break;

			// handler for regular results
			case RESULT:
				System.out.println(m);
				break;

			// fallback handler for unexpected message types.
			default:
				System.out.println("Unhandled: " + m.getType());
				break;
			}
		}, Stores.current());

		// send a couple of queries, wait for results
		pc.sendQuery(RT.cvm("A beautiful life - something special - a magic moment"));
		pc.sendQuery(RT.cvm(1L));
		Thread.sleep(3000);
		pc.close();

		System.exit(0);
	}

}
