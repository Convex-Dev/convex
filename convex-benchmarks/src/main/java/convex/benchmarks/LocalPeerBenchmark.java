package convex.benchmarks;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.Options;

import convex.api.Convex;
import convex.api.ConvexLocal;
import convex.core.Result;
import convex.core.State;
import convex.core.crypto.AKeyPair;
import convex.core.data.AccountKey;
import convex.core.data.Address;
import convex.core.data.prim.CVMLong;
import convex.core.init.Init;
import convex.core.lang.ops.Constant;
import convex.core.transactions.Invoke;
import convex.core.util.Utils;
import convex.peer.API;
import convex.peer.Server;

/**
 * Benchmark for applying transactions to CVM state. This is measuring the end-to-end time for processing
 * transactions themselves on the CVM.
 *
 * Skips stuff around transactions, block overhead, signatures etc.
 */
public class LocalPeerBenchmark {

	static AKeyPair HERO_KP=Benchmarks.HERO_KEYPAIR;
	static AccountKey HERO_KEY=HERO_KP.getAccountKey();
	static AKeyPair PEER_KP=AKeyPair.createSeeded(222);
	
	public static final AKeyPair[] KEYPAIRS = new AKeyPair[] {
			PEER_KP
		};
		
	public static ArrayList<AKeyPair> PEER_KEYPAIRS=(ArrayList<AKeyPair>) Arrays.asList(KEYPAIRS).stream().collect(Collectors.toList());
	public static ArrayList<AccountKey> PEER_KEYS=(ArrayList<AccountKey>) Arrays.asList(KEYPAIRS).stream().map(kp->kp.getAccountKey()).collect(Collectors.toList());

	
	private static State GENESIS=Init.createBaseState(PEER_KEYS);
	private static Server SERVER;
	private static Address HERO;
	private static ConvexLocal PEER_CLIENT;
	private static ConvexLocal CONVEX;
	
	static {
		try {
			List<Server> servers=API.launchLocalPeers(PEER_KEYPAIRS, GENESIS, null, null);

			SERVER=servers.get(0);
			PEER_CLIENT=Convex.connect(SERVER, Init.GENESIS_ADDRESS, PEER_KP);
			String cmd="(let [ha (create-account "+HERO_KEY+")] (transfer ha 1000000000000000) ha)";
			Result hr=PEER_CLIENT.transactSync(cmd);
			if (hr.isError()) {
				throw new Error("Transaction Failed: "+hr.toString());
			}
			HERO=hr.getValue();
			CONVEX=Convex.connect(SERVER, HERO, HERO_KP);
		} catch (Throwable t) {
			Utils.sneakyThrow(t);
		}
	}

	@Benchmark
	public void constantOp() throws TimeoutException, IOException {
		Result r=CONVEX.transactSync(Invoke.create(HERO, 0, Constant.create(CVMLong.ONE)));
		if (r.isError()) {
			throw new Error("Transaction Failed: "+r.toString());
		}
	}

	public static void main(String[] args) throws Exception {
		Options opt = Benchmarks.createOptions(LocalPeerBenchmark.class);
		new Runner(opt).run();
	}
}
