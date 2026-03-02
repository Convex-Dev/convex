package convex.examples;

import java.util.HashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import convex.api.ConvexLocal;
import convex.core.Result;
import convex.core.crypto.AKeyPair;
import convex.core.cvm.Address;
import convex.core.cvm.Context;
import convex.core.cvm.Keywords;
import convex.core.cvm.State;
import convex.core.data.ACell;
import convex.core.data.Keyword;
import convex.core.init.Init;
import convex.core.lang.Reader;
import convex.core.util.Utils;
import convex.peer.API;
import convex.peer.Server;

/**
 * Query throughput measurement for a single peer.
 *
 * Pre-compiles queries to CVM ops, then measures throughput through the full
 * server query pipeline (ConvexLocal → deliverMessage → QueryHandler → Peer.executeQuery).
 *
 * Compares sync vs async and different query complexities.
 */
public class QueryThroughput {

	static final int TOTAL_QUERIES = 1_000_000;
	static final int NUM_CLIENTS = 20;
	static final int QUERIES_PER_CLIENT = TOTAL_QUERIES / NUM_CLIENTS;

	static final String[] QUERY_NAMES = {
		"Constant (42)",
		"Arithmetic (+)",
		"Loop x100",
	};
	static final String[] QUERY_SOURCES = {
		"42",
		"(+ 1 2 3)",
		"(loop [i 0] (if (< i 100) (recur (inc i)) i))",
	};

	public static void main(String[] args) throws Exception {
		boolean async = false;
		int queryIndex = 0;
		for (String arg : args) {
			if ("--async".equals(arg)) async = true;
			if ("--q1".equals(arg)) queryIndex = 1;
			if ("--q2".equals(arg)) queryIndex = 2;
		}

		System.out.println("=== Query Throughput Test ===");
		System.out.println("Mode:      " + (async ? "ASYNC" : "SYNC"));
		System.out.println("Query:     " + QUERY_NAMES[queryIndex]);
		System.out.println("Total:     " + TOTAL_QUERIES);
		System.out.println("Clients:   " + NUM_CLIENTS);
		System.out.println("Per client: " + QUERIES_PER_CLIENT);
		System.out.println();

		// --- Launch single peer ---
		AKeyPair peerKP = AKeyPair.generate();
		State genesis = Init.createState(java.util.List.of(peerKP.getAccountKey()));
		HashMap<Keyword, Object> config = new HashMap<>();
		config.put(Keywords.KEYPAIR, peerKP);
		config.put(Keywords.STATE, genesis);
		Server server = API.launchPeer(config);
		System.out.println("Peer launched on port " + server.getPort());

		// --- Pre-compile query ---
		Address address = Init.GENESIS_ADDRESS;
		Context ctx = Context.create(genesis, address);
		ACell form = Reader.read(QUERY_SOURCES[queryIndex]);
		ACell compiled = ctx.expandCompile(form).getResult();
		System.out.println("Query compiled: " + QUERY_SOURCES[queryIndex]);

		// --- Create clients ---
		ConvexLocal[] clients = new ConvexLocal[NUM_CLIENTS];
		for (int i = 0; i < NUM_CLIENTS; i++) {
			clients[i] = ConvexLocal.create(server, address, peerKP);
		}
		System.out.println("Created " + NUM_CLIENTS + " local clients");
		System.out.println();

		// --- Run benchmark ---
		System.out.println("Submitting " + TOTAL_QUERIES + " queries...");
		long startTime = Utils.getTimeMillis();
		AtomicInteger completedCount = new AtomicInteger(0);
		AtomicInteger errorCount = new AtomicInteger(0);

		if (async) {
			runAsync(clients, compiled, address, completedCount, errorCount, startTime);
		} else {
			runSync(clients, compiled, address, completedCount, errorCount, startTime);
		}

		long totalTime = Utils.getTimeMillis() - startTime;

		// --- Report ---
		int succeeded = completedCount.get();
		int errors = errorCount.get();
		long qps = succeeded * 1000L / Math.max(1, totalTime);

		System.out.println();
		System.out.println("=== Results ===");
		System.out.println("Mode:       " + (async ? "ASYNC" : "SYNC"));
		System.out.println("Query:      " + QUERY_NAMES[queryIndex]);
		System.out.println("Total time: " + totalTime + "ms");
		System.out.println("Succeeded:  " + succeeded);
		System.out.println("Errors:     " + errors);
		System.out.println("Throughput: " + qps + " QPS");
		System.out.println();

		for (ConvexLocal c : clients) c.close();
		server.close();
		System.out.println("Done.");
		System.exit(0);
	}

	private static void runSync(ConvexLocal[] clients, ACell query, Address address,
			AtomicInteger completed, AtomicInteger errors, long startTime) throws InterruptedException {

		Thread[] workers = new Thread[NUM_CLIENTS];
		for (int c = 0; c < NUM_CLIENTS; c++) {
			final ConvexLocal client = clients[c];
			workers[c] = Thread.startVirtualThread(() -> {
				try {
					for (int i = 0; i < QUERIES_PER_CLIENT; i++) {
						Result r = client.querySync(query, address);
						if (r.isError()) {
							errors.incrementAndGet();
						} else {
							completed.incrementAndGet();
						}
					}
				} catch (Exception e) {
					System.err.println("Client error: " + e.getMessage());
				}
			});
		}

		// Progress reporter
		Thread progress = startProgressReporter(completed, errors, startTime);
		for (Thread w : workers) w.join();
		progress.interrupt();
	}

	private static void runAsync(ConvexLocal[] clients, ACell query, Address address,
			AtomicInteger completed, AtomicInteger errors, long startTime) throws InterruptedException {

		@SuppressWarnings("unchecked")
		CompletableFuture<Result>[][] futures = new CompletableFuture[NUM_CLIENTS][QUERIES_PER_CLIENT];

		AtomicInteger submitted = new AtomicInteger(0);
		Thread[] workers = new Thread[NUM_CLIENTS];
		for (int c = 0; c < NUM_CLIENTS; c++) {
			final int ci = c;
			final ConvexLocal client = clients[ci];
			workers[c] = Thread.startVirtualThread(() -> {
				for (int i = 0; i < QUERIES_PER_CLIENT; i++) {
					futures[ci][i] = client.query(query, address);
					submitted.incrementAndGet();
				}
			});
		}

		// Progress reporter
		Thread progress = startProgressReporter(submitted, errors, startTime);

		// Wait for all senders
		for (Thread w : workers) w.join();
		long submitTime = Utils.getTimeMillis() - startTime;
		System.out.println("All " + submitted.get() + " submitted in " + submitTime + "ms");

		// Wait for all results
		System.out.println("Awaiting results...");
		CompletableFuture<?>[] allFutures = new CompletableFuture[TOTAL_QUERIES];
		for (int c = 0; c < NUM_CLIENTS; c++) {
			System.arraycopy(futures[c], 0, allFutures, c * QUERIES_PER_CLIENT, QUERIES_PER_CLIENT);
		}
		try {
			CompletableFuture.allOf(allFutures).get(120, TimeUnit.SECONDS);
		} catch (Exception e) {
			System.out.println("  Deadline reached — collecting partial results");
		}

		progress.interrupt();

		// Tally
		for (int c = 0; c < NUM_CLIENTS; c++) {
			for (int i = 0; i < QUERIES_PER_CLIENT; i++) {
				if (futures[c][i].isDone()) {
					try {
						Result r = futures[c][i].getNow(null);
						if (r != null && r.isError()) {
							errors.incrementAndGet();
						} else {
							completed.incrementAndGet();
						}
					} catch (Exception e) {
						errors.incrementAndGet();
					}
				} else {
					errors.incrementAndGet();
				}
			}
		}
	}

	private static Thread startProgressReporter(AtomicInteger count, AtomicInteger errors, long startTime) {
		return Thread.startVirtualThread(() -> {
			try {
				while (!Thread.currentThread().isInterrupted()) {
					Thread.sleep(1000);
					int c = count.get();
					int e = errors.get();
					long elapsed = Utils.getTimeMillis() - startTime;
					long qps = c * 1000L / Math.max(1, elapsed);
					System.out.println("  count=" + c + " errors=" + e +
							" qps=" + qps + " (" + elapsed + "ms)");
				}
			} catch (InterruptedException ex) {
				// done
			}
		});
	}
}
