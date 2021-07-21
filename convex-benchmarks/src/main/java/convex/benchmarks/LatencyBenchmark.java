package  convex.benchmarks;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.Options;

import convex.api.Convex;
import convex.core.Result;
import convex.core.data.Address;
import convex.core.data.Keywords;
import convex.core.data.Maps;
import convex.core.lang.ops.Constant;
import convex.core.transactions.Invoke;
import convex.peer.API;
import convex.peer.Server;

/**
 * Benchmark for full round-trip latencies
 */
public class LatencyBenchmark {
	
	static final Address HERO=Benchmarks.HERO;
	static final Address VILLAIN=Benchmarks.VILLAIN;


	static Server server;
	static Convex client;
	static Convex client2;
	static {
		server=API.launchPeer(Maps.hashMapOf(
				Keywords.STATE,Benchmarks.STATE,
				Keywords.KEYPAIR,Benchmarks.HERO_KEYPAIR));
		try {
			client=Convex.connect(server.getHostAddress(), HERO,Benchmarks.HERO_KEYPAIR);
			client2=Convex.connect(server.getHostAddress(), VILLAIN,Benchmarks.VILLAIN_KEYPAIR);
		} catch (IOException | TimeoutException e) {
			e.printStackTrace();
		}

	}

	@Benchmark
	public void roundTripTransaction() throws TimeoutException, IOException {
		client.transactSync(Invoke.create(Benchmarks.HERO,-1, Constant.of(1L)));
	}

	@Benchmark
	public void roundTripTwoTransactions() throws TimeoutException, IOException, InterruptedException, ExecutionException {
		Future<Result> r1=client.transact(Invoke.create(HERO,-1, Constant.of(1L)));
		Future<Result> r2=client2.transact(Invoke.create(VILLAIN,-1, Constant.of(1L)));
		r1.get(1000,TimeUnit.MILLISECONDS);
		r2.get(1000,TimeUnit.MILLISECONDS);
	}

	@Benchmark
	public void roundTrip10Transactions() throws TimeoutException, IOException, InterruptedException, ExecutionException {
		doTransactions(10);
	}

	@Benchmark
	public void roundTrip50Transactions() throws TimeoutException, IOException, InterruptedException, ExecutionException {
		doTransactions(50);
	}

	@Benchmark
	public void roundTrip1000Transactions() throws TimeoutException, IOException, InterruptedException, ExecutionException {
		doTransactions(1000);
	}

	@SuppressWarnings("unchecked")
	private void doTransactions(int n) throws IOException, InterruptedException, ExecutionException, TimeoutException {
		CompletableFuture<Result>[] rs=new CompletableFuture[n];
		for (int i=0; i<n; i++) {
			CompletableFuture<Result> f=client.transact(Invoke.create(HERO,-1, Constant.of(i)));
			rs[i]=f;
		}
		CompletableFuture.allOf(rs).get(1000,TimeUnit.MILLISECONDS);
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
