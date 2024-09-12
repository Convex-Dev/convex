package convex.restapi.test;

import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeoutException;

import convex.core.crypto.AKeyPair;
import convex.core.data.Address;
import convex.core.util.ThreadUtils;
import convex.core.util.Utils;
import convex.java.Convex;
import convex.java.JSON;
import convex.peer.API;
import convex.peer.Server;
import convex.restapi.RESTServer;

public class StressTest {

	static RESTServer server;
	static int port;
	static int CLIENTCOUNT = 100;
	static int TRANSCOUNT = 100;
	static AKeyPair KP = AKeyPair.generate();

	static {
		try {
			Server s = API.launchPeer();
			RESTServer rs = RESTServer.create(s);
			rs.start(0);
			port = rs.getPort();
			server = rs;
		} catch (Exception e) {
			throw Utils.sneakyThrow(e);
		}
	}

	public static void main(String... args) throws InterruptedException, ExecutionException, TimeoutException {
		try {
			Convex convex = Convex.connect("http://localhost:" + port);
			long startTime = Utils.getTimeMillis();

			ArrayList<Convex> clients = new ArrayList<>(CLIENTCOUNT);
			for (int i = 0; i < CLIENTCOUNT; i++) {
				AKeyPair kp = KP;
				Address clientAddr = convex.createAccount(kp);
				Convex cc = Convex.connect("http://localhost:" + port);
				cc.setAddress(clientAddr);
				cc.setKeyPair(kp);
				clients.add(cc);
			}

			long genTime = Utils.getTimeMillis();
			System.out.println(CLIENTCOUNT + " REST clients connected in " + compTime(startTime, genTime));

			ExecutorService ex = ThreadUtils.getVirtualExecutor();

			ArrayList<CompletableFuture<Object>> cfutures = ThreadUtils.futureMap(ex, cc -> {
				for (int i = 0; i < TRANSCOUNT; i++) {
					String source = "*timestamp*";
					cc.query(source);
				}
				return null;
			}, clients);
			// wait for everything to be sent
			ThreadUtils.awaitAll(cfutures);

			long queryTime = Utils.getTimeMillis();
			System.out.println(CLIENTCOUNT * TRANSCOUNT + " REST queries in " + compTime(queryTime, genTime));

			cfutures = ThreadUtils.futureMap(ex, cc -> {
				return cc.faucet(cc.getAddress(), 1000000);
			}, clients);
			// wait for everything to be sent
			ThreadUtils.awaitAll(cfutures);

			long faucetTime = Utils.getTimeMillis();
			System.out.println(CLIENTCOUNT + " Faucet transactions completed in " + compTime(faucetTime, queryTime));

			cfutures = ThreadUtils.futureMap(ex, cc -> {
				// System.out.println(cc.queryAccount());
				Map<String, Object> res = cc.transact("(def a 1)");
				if (res.get("errorCode") != null)
					throw new RuntimeException(JSON.toPrettyString(res));
				return res;
			}, clients);
			// wait for everything to be sent
			ThreadUtils.awaitAll(cfutures);

			long transTime = Utils.getTimeMillis();
			System.out.println(CLIENTCOUNT + " transactions executed in " + compTime(faucetTime, transTime));

		} catch (Exception t) {
			t.printStackTrace();
			throw Utils.sneakyThrow(t);
		}
	}

	private static String compTime(long a, long b) {
		long d = Math.abs(a - b);
		return d + "ms";
	}
}
