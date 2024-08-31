package  convex.benchmarks;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.Options;

import convex.api.Convex;
import convex.core.Coin;
import convex.core.Result;
import convex.core.crypto.AKeyPair;
import convex.core.data.Address;
import convex.core.lang.ops.Constant;
import convex.core.transactions.Invoke;
import convex.peer.API;
import convex.peer.Server;

/**
 * Benchmarks for full round-trip latencies on a small local Peer Network
 * 
 * Main purpose is to test clean, efficient execution of transactions when not under load.
 * 
 * Note: these are for a single client executing transactions in batches, and are
 * therefore not useful for estimating overall network throughput (which would have many
 * clients submitting transactions in parallel), and higher latency due to global hops etc.
 * 
 */
public class LatencyBenchmark {
	
	static Address HERO=null;
	static Address VILLAIN=null;
	static final AKeyPair[] KPS=new AKeyPair[] {AKeyPair.generate(),AKeyPair.generate(),AKeyPair.generate()};

	static Server server;
	static Convex client;
	static Convex client2;
	static Convex peer;
	static {
		try {
			List<Server> servers=API.launchLocalPeers(Benchmarks.PEER_KEYPAIRS, Benchmarks.STATE);
			server=servers.get(0);
			Thread.sleep(1000);
			peer=Convex.connect(server,server.getPeerController(),server.getKeyPair());
			HERO=peer.createAccountSync(KPS[0].getAccountKey());
			VILLAIN=peer.createAccountSync(KPS[1].getAccountKey());
			peer.transfer(HERO, Coin.EMERALD);
			peer.transfer(VILLAIN, Coin.EMERALD);
			
			client=Convex.connect(server.getHostAddress(), HERO,KPS[0]);
			client2=Convex.connect(server.getHostAddress(), VILLAIN,KPS[1]);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		} catch (Throwable e) {
			e.printStackTrace();
			throw new Error(e);
		} 

	}

	@Benchmark
	public void roundTripTransaction() throws TimeoutException, InterruptedException {
		client.transactSync(Invoke.create(Benchmarks.HERO,0, Constant.of(1L)));
		// System.out.println(server.getBroadcastCount());
	}

	@Benchmark
	public void roundTripTwoTransactions() throws TimeoutException, InterruptedException, ExecutionException {
		Future<Result> r1=client.transact(Invoke.create(HERO,0, Constant.of(1L)));
		Future<Result> r2=client2.transact(Invoke.create(VILLAIN,0, Constant.of(1L)));
		r1.get(1000,TimeUnit.MILLISECONDS);
		r2.get(1000,TimeUnit.MILLISECONDS);
	}

	@Benchmark
	public void roundTrip10Transactions() throws TimeoutException, InterruptedException, ExecutionException {
		doTransactions(10);
	}

	@Benchmark
	public void roundTrip50Transactions() throws TimeoutException,InterruptedException, ExecutionException {
		doTransactions(50);
	}

	@Benchmark
	public void roundTrip1000Transactions() throws TimeoutException, InterruptedException, ExecutionException {
		doTransactions(1000);
	}

	@SuppressWarnings("unchecked")
	private void doTransactions(int n) throws InterruptedException,  TimeoutException, ExecutionException {
		CompletableFuture<Result>[] rs=new CompletableFuture[n];
		for (int i=0; i<n; i++) {
			CompletableFuture<Result> f=client.transact(Invoke.create(HERO,0, Constant.of(i)));
			rs[i]=f;
		}
		CompletableFuture.allOf(rs).get(1000,TimeUnit.MILLISECONDS);
		Result r0=rs[0].get();
		if (r0.isError()) {
			throw new Error("Transaction failed: "+r0);
		}
	}

	@Benchmark
	public void roundTripQuery() throws TimeoutException, InterruptedException {
		client.querySync(Constant.of(1L));
	}


	public static void main(String[] args) throws Exception {
		Options opt = Benchmarks.createOptions(LatencyBenchmark.class);
		new Runner(opt).run();
	}
}
