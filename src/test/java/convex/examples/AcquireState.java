package convex.examples;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import convex.api.Convex;
import convex.core.Init;
import convex.core.crypto.Hash;
import convex.core.data.ACell;

public class AcquireState {

	public static void main(String[] args) throws IOException, InterruptedException, ExecutionException, TimeoutException {
		// Use a fresh store
		//EtchStore etch=EtchStore.createTemp("acquire-testing");
		//Stores.setCurrent(etch);
		
		InetSocketAddress hostAddress = new InetSocketAddress("convex.world",  43579);

		Convex convex = Convex.connect(hostAddress, Init.HERO,null);
		
		Hash h=Hash.fromHex("3c6c1968ea610b666434b532a27cb306a546fd24fa1e61b286605d213795b96e");
		
		ACell cell=convex.acquire(h).get(3000,TimeUnit.MILLISECONDS);

		System.out.println(cell);
	}
}
