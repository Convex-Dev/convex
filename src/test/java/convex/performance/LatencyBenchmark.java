package convex.performance;

import java.io.IOException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.Options;

import convex.api.Convex;
import convex.core.Init;
import convex.core.Result;
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
	static Convex client2;
	static {
		server=API.launchPeer();
		try {
			client=Convex.connect(server.getHostAddress(), Init.HERO,Init.HERO_KP);
			client2=Convex.connect(server.getHostAddress(), Init.VILLAIN,Init.VILLAIN_KP);
		} catch (IOException e) {
			e.printStackTrace();
		}
		
	}
	
	@Benchmark
	public void roundTripTransaction() throws TimeoutException, IOException {
		client.transactSync(Invoke.create(Init.HERO,-1, Constant.of(1L)));
	}
	
	@Benchmark
	public void roundTripTwoTransactions() throws TimeoutException, IOException, InterruptedException, ExecutionException {
		Future<Result> r1=client.transact(Invoke.create(Init.HERO,-1, Constant.of(1L)));
		Future<Result> r2=client2.transact(Invoke.create(Init.VILLAIN,-1, Constant.of(1L)));
		r1.get();
		r2.get();
	}
	
	@Benchmark
	public void roundTrip100Transactions() throws TimeoutException, IOException, InterruptedException, ExecutionException {
		doTransactions(100);
	}
	
	@SuppressWarnings("unchecked")
	private void doTransactions(int n) throws IOException, InterruptedException, ExecutionException, TimeoutException {
		Future<Result>[] rs=new Future[n];
		for (int i=0; i<n; i++) {
			Future<Result> f=client.transact(Invoke.create(Init.HERO,-1, Constant.of(1L)));
			rs[i]=f;
		}
		for (int i=0; i<n; i++) {
			rs[i].get(1000,TimeUnit.MILLISECONDS);
		}
	}

	@Benchmark
	public void roundTripQuery() throws TimeoutException, IOException, InterruptedException, ExecutionException {
		client.querySync(Constant.of(1L));
	}
	 

	public static void main(String[] args) throws Exception {
		Options opt = Benchmarks.createOptions(LatencyBenchmark.class);
		new Runner(opt).run();
	}
}
