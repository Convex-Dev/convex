package convex.benchmarks;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.Options;

import convex.api.Convex;
import convex.api.ConvexLocal;
import convex.api.ConvexRemote;
import convex.core.Result;
import convex.core.crypto.AKeyPair;
import convex.core.cvm.Keywords;
import convex.core.cvm.Migrations;
import convex.core.cvm.State;
import convex.core.cvm.ops.Constant;
import convex.core.data.ACell;
import convex.core.data.Keyword;
import convex.core.data.Maps;
import convex.core.init.Init;
import convex.core.store.MemoryStore;
import convex.peer.API;
import convex.peer.Server;

/**
 * Query round trips through a local and a remote client of one in-process peer:
 * a caller waiting in querySync, a caller waiting on an async query, and a
 * callback that sends a second query from the first one's reply. Run with
 * several threads ({@code -t}) to see the effect under load.
 */
@org.openjdk.jmh.annotations.State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
public class ClientCallbackBenchmark {

	private static final ACell QUERY=Constant.of(1L);

	private MemoryStore store;
	private Server server;
	private ConvexLocal local;
	private ConvexRemote remote;

	@Setup(Level.Trial)
	public void setup() throws Exception {
		AKeyPair kp=AKeyPair.generate();
		State genesis=Migrations.applyAll(Init.createState(List.of(kp.getAccountKey())));
		store=new MemoryStore();
		Map<Keyword,Object> config=Maps.hashMapOf(Keywords.KEYPAIR,kp,Keywords.STATE,genesis,Keywords.STORE,store,
			Keywords.PORT,0,Keywords.AUTO_MANAGE,false,Keywords.OUTGOING_CONNECTIONS,0);
		server=API.launchPeer(config);
		local=Convex.connect(server);
		remote=Convex.connect(server.getHostAddress());
	}

	@TearDown(Level.Trial)
	public void tearDown() {
		remote.close();
		local.close();
		server.close();
		store.close();
	}

	@Benchmark
	public Result localQuerySync() throws InterruptedException {
		return local.querySync(QUERY);
	}

	@Benchmark
	public Result localQueryGet() throws Exception {
		return local.query(QUERY).get();
	}

	@Benchmark
	public Result localChainedQuery() throws Exception {
		return local.query(QUERY).thenCompose(r -> local.query(QUERY)).get();
	}

	@Benchmark
	public Result remoteQuerySync() throws InterruptedException {
		return remote.querySync(QUERY);
	}

	@Benchmark
	public Result remoteQueryGet() throws Exception {
		return remote.query(QUERY).get();
	}

	@Benchmark
	public Result remoteChainedQuery() throws Exception {
		return remote.query(QUERY).thenCompose(r -> remote.query(QUERY)).get();
	}

	public static void main(String[] args) throws Exception {
		Options opt = Benchmarks.createOptions(ClientCallbackBenchmark.class);
		new Runner(opt).run();
	}
}
