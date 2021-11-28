package convex.examples;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import convex.api.Convex;
import convex.core.Result;
import convex.core.State;

public class TestnetClient {

	public static void main(String[] args) throws IOException, InterruptedException, ExecutionException, TimeoutException {
		// Use a fresh store
		//EtchStore etch=EtchStore.createTemp("acquire-testing");
		//Stores.setCurrent(etch);

		// InetSocketAddress hostAddress = new InetSocketAddress("convex.world",  18888);
		InetSocketAddress hostAddress = new InetSocketAddress("localhost",  50817);

		Convex convex = Convex.connect(hostAddress, null,null);

		Future<Result> fr=convex.query("(+ 2 3)");
 		Result r=fr.get();

		System.out.println(r);
	}
}
