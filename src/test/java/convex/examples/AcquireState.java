package convex.examples;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import convex.api.Convex;
import convex.core.data.ACell;
import convex.core.data.Hash;
import convex.core.init.InitConfigTest;

public class AcquireState {

	public static void main(String[] args) throws IOException, InterruptedException, ExecutionException, TimeoutException {
		// Use a fresh store
		//EtchStore etch=EtchStore.createTemp("acquire-testing");
		//Stores.setCurrent(etch);

		InetSocketAddress hostAddress = new InetSocketAddress("convex.world",  43579);

		InitConfigTest initConfigTest = InitConfigTest.create();
		Convex convex = Convex.connect(hostAddress, InitConfigTest.HERO_ADDRESS,null);

		Hash h=Hash.fromHex("3c6c1968ea610b666434b532a27cb306a546fd24fa1e61b286605d213795b96e");

		ACell cell=convex.acquire(h).get(3000,TimeUnit.MILLISECONDS);

		System.out.println(cell);
	}
}
