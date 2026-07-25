package convex.benchmarks;

import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import convex.core.crypto.AKeyPair;
import convex.core.cvm.Address;
import convex.core.cvm.Context;
import convex.core.cvm.Peer;
import convex.core.cvm.State;
import convex.core.data.ACell;
import convex.core.lang.Reader;

/**
 * Standalone throughput probe for the underlying CVM query evaluation — the same
 * {@link Peer#executeQuery} path the REST query lane uses, with the HTTP layer stripped
 * away. This is <b>not</b> a JMH benchmark and <b>not</b> a JUnit test; run its
 * {@link #main} to measure raw eval throughput and how it scales across threads, so a core
 * regression (slower eval, or lost thread scaling) is easy to spot and re-check.
 *
 * <p>Queries run against an immutable consensus State, so they are read-only and should
 * scale close to linearly with threads; a poor scaling factor points at accidental
 * contention on a shared structure.</p>
 *
 * <p>Two paths are measured, each single-threaded then across {@code -Dthreads}:</p>
 * <ul>
 *   <li><b>Peer.executeQuery</b> — the full query path (wrap form in a fake Invoke, apply
 *       against consensus state with juice accounting), exactly what the server calls.</li>
 *   <li><b>Context.eval</b> — raw CVM evaluation of the form against the state.</li>
 * </ul>
 *
 * <pre>
 *   -Dthreads=10          parallel threads (a 1-thread baseline is always run too)
 *   -Diterations=1000000  queries per thread per round
 *   -Drounds=2            measured rounds
 *   -Dsource=(* 2 3)      query source expression
 * </pre>
 *
 * <pre>
 *   mvn -pl convex-benchmarks -am compile
 *   mvn -pl convex-benchmarks org.codehaus.mojo:exec-maven-plugin:3.1.0:java \
 *       -Dexec.mainClass=convex.benchmarks.CVMQueryBenchmark -Dthreads=10
 * </pre>
 */
public class CVMQueryBenchmark {

	public static void main(String[] args) throws Exception {
		int threads=Integer.getInteger("threads",10);
		long iterations=Long.getLong("iterations",1_000_000L);
		int rounds=Integer.getInteger("rounds",2);
		String source=System.getProperty("source","(* 2 3)");

		State state=Benchmarks.STATE;
		AKeyPair kp=Benchmarks.FIRST_PEER_KEYPAIR;
		Peer peer=Peer.create(kp, state);
		Address addr=Benchmarks.HERO;
		ACell form=Reader.read(source);

		Supplier<Object> queryPath=() -> peer.executeQuery(form, addr).context.getValue();
		Supplier<Object> rawEval=() -> Context.create(state, addr).eval(form).getValue();

		Object probe=queryPath.get();
		System.out.println("Query: "+source+"  =>  "+probe);
		System.out.println("Cores="+Runtime.getRuntime().availableProcessors()
				+"  threads="+threads+"  iterations/thread="+iterations+"  rounds="+rounds);

		bench("Peer.executeQuery (full query path)", queryPath, threads, iterations, rounds);
		bench("Context.eval (raw CVM eval)", rawEval, threads, iterations, rounds);

		System.exit(0);
	}

	private static void bench(String title, Supplier<Object> task, int threads, long iterations, int rounds)
			throws Exception {
		System.out.println("\n== "+title+" ==");
		runRound(task, 1, Math.min(iterations,500_000L), "warmup");
		double base=0;
		for (int r=1;r<=rounds;r++) base=runRound(task, 1, iterations, "1-thread r"+r);
		double par=0;
		for (int r=1;r<=rounds;r++) par=runRound(task, threads, iterations, threads+"-thread r"+r);
		System.out.printf("  scaling: %,.0f -> %,.0f q/s  =  %.1fx across %d threads (ideal %d.0x)%n",
				base, par, par/base, threads, threads);
	}

	/** Runs one round: {@code threads} workers each perform {@code iterations} queries. Returns q/s. */
	private static double runRound(Supplier<Object> task, int threads, long iterations, String label)
			throws Exception {
		CyclicBarrier gate=new CyclicBarrier(threads+1);
		AtomicLong consumed=new AtomicLong();
		Thread[] ws=new Thread[threads];
		for (int i=0;i<threads;i++) {
			ws[i]=new Thread(() -> {
				try { gate.await(); } catch (Exception e) { throw new RuntimeException(e); }
				long c=0;
				for (long j=0;j<iterations;j++) {
					Object v=task.get();
					if (v!=null) c++;  // consume the result so the JIT cannot elide the query
				}
				consumed.addAndGet(c);
			});
			ws[i].start();
		}
		gate.await();
		long start=System.nanoTime();
		for (Thread t:ws) t.join();
		long elapsed=System.nanoTime()-start;
		if (consumed.get()==0) throw new IllegalStateException("no results consumed — query produced null");

		long total=iterations*threads;
		double qps=total/(elapsed/1e9);
		System.out.printf("  %-12s %,13d in %6.3fs  =>  %,12.0f q/s  (%,.0f /thread)%n",
				label, total, elapsed/1e9, qps, qps/threads);
		return qps;
	}
}
