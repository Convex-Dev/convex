package convex.examples;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import convex.api.Convex;
import convex.core.State;

public class AcquireState {

	public static void main(String[] args) throws IOException, InterruptedException, ExecutionException, TimeoutException {
		// Use a fresh store
		//EtchStore etch=EtchStore.createTemp("acquire-testing");
		//Stores.setCurrent(etch);

		InetSocketAddress hostAddress = new InetSocketAddress("convex.world",  43579);

		Convex convex = Convex.connect(hostAddress, null,null);

		State state=convex.acquireState().get(5000, TimeUnit.MILLISECONDS);

		System.out.println(state);
	}
}
