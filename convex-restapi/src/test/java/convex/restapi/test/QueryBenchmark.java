package convex.restapi.test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;

import convex.core.crypto.AKeyPair;
import convex.core.cvm.Keywords;
import convex.core.init.Init;
import convex.peer.API;
import convex.peer.Server;
import convex.restapi.RESTConfig;
import convex.restapi.RESTServer;

/**
 * Standalone load generator for the public REST query lane. This is <b>not</b> a JUnit
 * test (no {@code @Test} methods, so Surefire ignores it) — run its {@link #main} to burst
 * queries at a server and report throughput. Re-run any time to compare.
 *
 * <p>By default it launches a local peer + REST server in-process and drives it. Because
 * that server shares this JVM's cores with the client, local numbers are a floor — a
 * dedicated server on separate hardware will do better. Pass {@code -Durl=http://host:port}
 * to benchmark an already-running server instead.</p>
 *
 * <p>Tunables (JVM system properties):</p>
 * <pre>
 *   -Dqueries=200000       total queries per round
 *   -Dconcurrency=256      client requests kept in flight
 *   -Drounds=3             measured rounds (after one warmup round)
 *   -Dsource=(* 2 3)       query source expression
 *   -Durl=                 external base URL; if unset, a local server is launched
 *   -DqueryConcurrency=    server rest.query.maxConcurrent (local only; default = cores)
 * </pre>
 *
 * <p>Run from an IDE (just launch {@code main}) or from Maven:</p>
 * <pre>
 *   mvn -pl convex-restapi -am test-compile
 *   mvn -pl convex-restapi org.codehaus.mojo:exec-maven-plugin:3.1.0:java \
 *       -Dexec.classpathScope=test -Dexec.mainClass=convex.restapi.test.QueryBenchmark \
 *       -Dqueries=200000 -Dconcurrency=256
 * </pre>
 */
public class QueryBenchmark {

	public static void main(String[] args) throws Exception {
		long queries=Long.getLong("queries",200_000L);
		int concurrency=Integer.getInteger("concurrency",256);
		int rounds=Integer.getInteger("rounds",3);
		String source=System.getProperty("source","(* 2 3)");
		String url=System.getProperty("url");
		int cores=Runtime.getRuntime().availableProcessors();

		RESTServer local=null;
		Server peer=null;
		try {
			String base;
			long addr;
			if (url!=null) {
				base=url;
				addr=Long.getLong("address", Init.GENESIS_ADDRESS.longValue());
				System.out.println("Target: external "+base);
			} else {
				int qc=Integer.getInteger("queryConcurrency", cores);
				RESTConfig config=RESTConfig.parse("{\"rest\":{\"maxConcurrentRequests\":100000,"
						+"\"query\":{\"maxConcurrent\":"+qc+"}}}");
				var launchConfig=config.toLegacy();
				launchConfig.put(Keywords.KEYPAIR,AKeyPair.generate());
				peer=API.launchPeer(launchConfig);
				local=RESTServer.create(peer);
				local.start(0);
				base="http://localhost:"+local.getPort();
				addr=Init.GENESIS_ADDRESS.longValue();
				System.out.println("Target: local in-process server on "+base
						+"  (cores="+cores+", rest.query.maxConcurrent="+qc+")");
			}

			byte[] body=("{\"address\":\""+addr+"\",\"source\":\""+escape(source)+"\"}")
					.getBytes(StandardCharsets.UTF_8);
			URI uri=URI.create(base+"/api/v1/query");

			HttpClient client=HttpClient.newBuilder()
					.version(HttpClient.Version.HTTP_1_1)
					.connectTimeout(Duration.ofSeconds(10))
					.build();

			System.out.println("Config: queries="+queries+" concurrency="+concurrency+" rounds="+rounds);
			System.out.println("Query:  POST "+uri+"  body="+new String(body,StandardCharsets.UTF_8));

			// Probe once so a bad address/source is obvious before the timed runs
			HttpResponse<String> probe=client.send(request(uri,body),HttpResponse.BodyHandlers.ofString());
			System.out.println("Probe:  HTTP "+probe.statusCode()+"  result="+probe.body());
			if (probe.statusCode()!=200) System.out.println("WARNING: probe was not HTTP 200");

			runRound(client,uri,body,Math.min(queries,20_000),concurrency,"warmup");
			for (int r=1;r<=rounds;r++) runRound(client,uri,body,queries,concurrency,"round "+r);
		} finally {
			if (local!=null) local.close();
			if (peer!=null) peer.close();
		}
		// A launched peer keeps non-daemon threads alive; exit explicitly so the tool ends.
		System.exit(0);
	}

	/** Runs one timed round: {@code concurrency} virtual-thread workers each send an equal share. */
	private static void runRound(HttpClient client, URI uri, byte[] body, long totalQueries,
			int concurrency, String label) throws InterruptedException {
		int perWorker=(int) Math.max(1, totalQueries/concurrency);
		int total=perWorker*concurrency;
		long[][] lat=new long[concurrency][perWorker];
		AtomicInteger ok=new AtomicInteger(), shed=new AtomicInteger(), fail=new AtomicInteger();

		Thread[] workers=new Thread[concurrency];
		long start=System.nanoTime();
		for (int w=0;w<concurrency;w++) {
			final long[] mine=lat[w];
			workers[w]=Thread.ofVirtual().start(() -> {
				HttpRequest req=request(uri,body);
				for (int j=0;j<perWorker;j++) {
					long t0=System.nanoTime();
					int sc;
					try {
						sc=client.send(req,HttpResponse.BodyHandlers.discarding()).statusCode();
					} catch (Exception e) {
						sc=-1;
					}
					mine[j]=System.nanoTime()-t0;
					if (sc==200) ok.incrementAndGet();
					else if (sc==503) shed.incrementAndGet();
					else fail.incrementAndGet();
				}
			});
		}
		for (Thread t:workers) t.join();
		long elapsedNanos=System.nanoTime()-start;

		long[] all=new long[total];
		int p=0;
		for (long[] m:lat) { System.arraycopy(m,0,all,p,m.length); p+=m.length; }
		Arrays.sort(all);
		double secs=elapsedNanos/1e9;
		System.out.printf(
			"%-8s %,9d in %6.2fs  =>  %,10.0f q/s  |  200=%,d 503=%,d err=%,d  |  p50=%.2f p99=%.2f max=%.1f ms%n",
			label, (long) total, secs, total/secs, ok.get(), shed.get(), fail.get(),
			ms(all[(int)(total*0.50)]), ms(all[(int)(total*0.99)]), ms(all[total-1]));
	}

	private static HttpRequest request(URI uri, byte[] body) {
		return HttpRequest.newBuilder(uri)
				.header("Content-Type","application/json")
				.POST(HttpRequest.BodyPublishers.ofByteArray(body))
				.build();
	}

	private static double ms(long nanos) { return nanos/1e6; }

	private static String escape(String s) {
		return s.replace("\\","\\\\").replace("\"","\\\"");
	}
}
