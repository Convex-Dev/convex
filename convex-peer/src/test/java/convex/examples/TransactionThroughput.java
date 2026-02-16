package convex.examples;

import java.util.HashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import convex.api.ConvexLocal;
import convex.core.Result;
import convex.core.crypto.AKeyPair;
import convex.core.cvm.Address;
import convex.core.cvm.Keywords;
import convex.core.cvm.State;
import convex.core.cvm.transactions.ATransaction;
import convex.core.cvm.transactions.Transfer;
import convex.core.data.Keyword;
import convex.core.data.SignedData;
import convex.core.init.Init;
import convex.core.util.Utils;
import convex.peer.API;
import convex.peer.Server;
import convex.peer.TransactionHandler;

/**
 * Transaction throughput measurement for a single local peer.
 *
 * Pre-signs all transactions, then fires them through ConvexLocal and
 * measures end-to-end pipeline throughput (TransactionHandler -> BeliefPropagator
 * -> CVMExecutor -> result return).
 *
 * Senders submit in a tight loop from virtual threads. Backpressure from the
 * server (blocking offer on txMessageQueue) naturally throttles submission
 * when the pipeline is saturated.
 *
 * Usage: run main(). Prints diagnostics to stdout.
 */
public class TransactionThroughput {

	static final int TOTAL_TRANSACTIONS = 200_000;

	/** Number of client accounts sending transactions concurrently */
	static final int NUM_CLIENTS = 20;

	/** Transactions per client */
	static final int TXN_PER_CLIENT = TOTAL_TRANSACTIONS / NUM_CLIENTS;

	public static void main(String[] args) throws Exception {
		System.out.println("=== Transaction Throughput Test ===");
		System.out.println("Transactions: " + TOTAL_TRANSACTIONS);
		System.out.println("Clients:      " + NUM_CLIENTS);
		System.out.println("Per client:   " + TXN_PER_CLIENT);
		System.out.println();

		// --- Launch single peer ---
		AKeyPair peerKP = AKeyPair.generate();
		State genesis = Init.createState(java.util.List.of(peerKP.getAccountKey()));
		HashMap<Keyword, Object> config = new HashMap<>();
		config.put(Keywords.KEYPAIR, peerKP);
		config.put(Keywords.STATE, genesis);
		config.put(Keywords.FAUCET, true);
		Server server = API.launchPeer(config);

		System.out.println("Peer launched on port " + server.getPort());

		// --- Create client accounts ---
		ConvexLocal admin = ConvexLocal.create(server, Init.GENESIS_PEER_ADDRESS, peerKP);

		AKeyPair[] clientKPs = new AKeyPair[NUM_CLIENTS];
		Address[] clientAddrs = new Address[NUM_CLIENTS];
		for (int i = 0; i < NUM_CLIENTS; i++) {
			clientKPs[i] = AKeyPair.generate();
			Address addr = admin.createAccountSync(clientKPs[i].getAccountKey());
			admin.transactSync("(transfer " + addr + " 100000000)");
			clientAddrs[i] = addr;
		}
		System.out.println("Created " + NUM_CLIENTS + " client accounts");

		// Target address for transfers
		Address target = Address.create(0);

		// --- Pre-sign all transactions ---
		System.out.print("Pre-signing " + TOTAL_TRANSACTIONS + " transactions... ");
		long signStart = Utils.getTimeMillis();

		@SuppressWarnings("unchecked")
		SignedData<ATransaction>[][] allSigned = new SignedData[NUM_CLIENTS][TXN_PER_CLIENT];
		for (int c = 0; c < NUM_CLIENTS; c++) {
			long seq = admin.getSequence(clientAddrs[c]);
			for (int t = 0; t < TXN_PER_CLIENT; t++) {
				Transfer tx = Transfer.create(clientAddrs[c], seq + 1 + t, target, 1);
				allSigned[c][t] = clientKPs[c].signData(tx);
			}
		}

		long signTime = Utils.getTimeMillis() - signStart;
		System.out.println("done in " + signTime + "ms (" +
				(TOTAL_TRANSACTIONS * 1000L / Math.max(1, signTime)) + " signs/sec)");

		// --- Create local clients ---
		ConvexLocal[] clients = new ConvexLocal[NUM_CLIENTS];
		for (int c = 0; c < NUM_CLIENTS; c++) {
			clients[c] = ConvexLocal.create(server, clientAddrs[c], clientKPs[c]);
		}

		TransactionHandler th = server.getTransactionHandler();

		// --- Submit all transactions ---
		System.out.println();
		System.out.println("Submitting...");
		long sendStart = Utils.getTimeMillis();

		// Collect all futures
		@SuppressWarnings("unchecked")
		CompletableFuture<Result>[][] futures = new CompletableFuture[NUM_CLIENTS][TXN_PER_CLIENT];
		AtomicInteger submitted = new AtomicInteger(0);

		// Each client submits from its own virtual thread.
		// ConvexLocal.transact() blocks via backpressure when txMessageQueue is full.
		Thread[] senders = new Thread[NUM_CLIENTS];
		for (int c = 0; c < NUM_CLIENTS; c++) {
			final int ci = c;
			senders[c] = Thread.startVirtualThread(() -> {
				for (int t = 0; t < TXN_PER_CLIENT; t++) {
					futures[ci][t] = clients[ci].transact(allSigned[ci][t]);
					submitted.incrementAndGet();
				}
			});
		}

		// Progress reporter
		Thread progress = Thread.startVirtualThread(() -> {
			try {
				while (!Thread.currentThread().isInterrupted()) {
					Thread.sleep(1000);
					int sub = submitted.get();
					long elapsed = Utils.getTimeMillis() - sendStart;
					System.out.println("  submitted=" + sub +
							" interests=" + th.countInterests() +
							" received=" + th.receivedTransactionCount +
							" client=" + th.clientTransactionCount +
							" (" + elapsed + "ms)");
				}
			} catch (InterruptedException e) {
				// done
			}
		});

		// Wait for all senders to finish submitting
		for (Thread s : senders) s.join();
		long submitTime = Utils.getTimeMillis() - sendStart;
		System.out.println("All " + submitted.get() + " submitted in " + submitTime + "ms");

		// Wait for all results
		System.out.println("Awaiting results...");
		CompletableFuture<?>[] allFutures = new CompletableFuture[TOTAL_TRANSACTIONS];
		for (int c = 0; c < NUM_CLIENTS; c++) {
			System.arraycopy(futures[c], 0, allFutures, c * TXN_PER_CLIENT, TXN_PER_CLIENT);
		}
		try {
			CompletableFuture.allOf(allFutures).get(60, TimeUnit.SECONDS);
		} catch (java.util.concurrent.TimeoutException e) {
			System.out.println("  Deadline reached — collecting partial results");
		}

		progress.interrupt();
		long totalTime = Utils.getTimeMillis() - sendStart;

		// --- Tally results ---
		int succeeded = 0;
		int errCount = 0;
		int timeoutCount = 0;
		HashMap<String, Integer> errorCounts = new HashMap<>();
		for (int c = 0; c < NUM_CLIENTS; c++) {
			for (int t = 0; t < TXN_PER_CLIENT; t++) {
				if (!futures[c][t].isDone()) {
					timeoutCount++;
				} else {
					try {
						Result r = futures[c][t].getNow(null);
						if (r != null && r.isError()) {
							errCount++;
							String code = String.valueOf(r.getErrorCode());
							errorCounts.merge(code, 1, Integer::sum);
						} else {
							succeeded++;
						}
					} catch (Exception e) {
						errCount++;
						errorCounts.merge(e.getClass().getSimpleName(), 1, Integer::sum);
					}
				}
			}
		}

		// --- Report ---
		long tps = succeeded * 1000L / Math.max(1, totalTime);

		System.out.println();
		System.out.println("=== Results ===");
		System.out.println("Total time:     " + totalTime + "ms");
		System.out.println("Succeeded:      " + succeeded);
		System.out.println("Errors:         " + errCount);
		System.out.println("Timeouts:       " + timeoutCount);
		System.out.println("Throughput:     " + tps + " TPS (successful)");
		System.out.println();

		if (!errorCounts.isEmpty()) {
			System.out.println("Error breakdown:");
			errorCounts.forEach((k, v) -> System.out.println("  " + k + ": " + v));
			System.out.println();
		}

		System.out.println("Pipeline state:");
		System.out.println("  pending interests: " + th.countInterests());
		System.out.println("  received txns:     " + th.receivedTransactionCount);
		System.out.println("  client txns:       " + th.clientTransactionCount);

		server.close();
		System.out.println();
		System.out.println("Done.");
		System.exit(0);
	}
}
