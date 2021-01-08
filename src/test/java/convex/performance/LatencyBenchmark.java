package convex.performance;

import java.io.IOException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.Options;

import convex.api.Convex;
import convex.core.Init;
import convex.core.lang.ops.Constant;
import convex.core.transactions.Invoke;
import convex.peer.API;
import convex.peer.Server;

/**
 * Benchmark for full round-trip latencies
 */
public class LatencyBenchmark {
	
	static Server server;
	static Convex client;
	static {
		server=API.launchPeer();
		try {
			client=Convex.connect(server.getHostAddress(), Init.HERO_KP);
		} catch (IOException e) {
			e.printStackTrace();
		}
		
	}
	
	@Benchmark
	public void roundTripTransaction() throws TimeoutException, IOException {
		client.transactSync(Invoke.create(-1, Constant.create(1L)));
	}
	
	@Benchmark
	public void roundTripQuery() throws TimeoutException, IOException, InterruptedException, ExecutionException {
		client.querySync(Constant.create(1L));
	}
	 

	public static void main(String[] args) throws Exception {
		Options opt = Benchmarks.createOptions(LatencyBenchmark.class);
		new Runner(opt).run();
	}
}
