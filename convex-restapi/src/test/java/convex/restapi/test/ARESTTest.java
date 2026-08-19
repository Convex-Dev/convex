package convex.restapi.test;

import convex.api.Convex;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.fail;

import org.junit.jupiter.api.extension.ExtendWith;

import convex.core.ErrorCodes;
import convex.core.Result;
import convex.core.crypto.AKeyPair;
import convex.core.cvm.Address;
import convex.core.cvm.Keywords;
import convex.core.data.AccountKey;
import convex.core.init.Init;
import convex.core.util.Utils;
import convex.java.ConvexHTTP;
import convex.peer.API;
import convex.peer.Server;
import convex.restapi.RESTConfig;
import convex.restapi.RESTServer;

@ExtendWith(RESTFixtureExtension.class)
public abstract class ARESTTest {
	protected static RESTServer server;
	protected static int port;
	protected static String HOST_PATH;
	protected static String API_PATH;
	protected static AKeyPair KP;
	protected static AKeyPair CLIENT_KP=AKeyPair.createSeeded(568756);
	
	protected static final HttpClient httpClient = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(10))
			.build();
	
	static {
		try {
			// Security-sensitive optional services are disabled by default. The shared
			// integration fixture opts in only because dedicated tests exercise them.
			RESTConfig config=RESTConfig.parse("""
				{rest:{faucet:true,messageEndpoint:true},mcp:{signing:true,elevated:true}}
				""");
			var launchConfig=config.toLegacy();
			launchConfig.put(Keywords.KEYPAIR,AKeyPair.generate());
			Server s = API.launchPeer(launchConfig);
			RESTServer rs = RESTServer.create(s);
			rs.start(0);
			port = rs.getPort();
			server = rs;
			HOST_PATH="http://localhost:" + rs.getPort();
			API_PATH=HOST_PATH+"/api/v1";
			KP=s.getKeyPair();
		} catch (Exception e) {
			throw Utils.sneakyThrow(e);
		}
	}

	static synchronized void closeFixture() {
		RESTServer rs=server;
		if (rs==null) return;
		Server peer=rs.getServer();
		server=null;
		try {
			rs.close();
		} finally {
			if (peer!=null) peer.close();
			httpClient.shutdownNow();
			HOST_PATH=null;
			API_PATH=null;
			KP=null;
		}
	}
	
	/**
	 * Helper method to make HTTP GET requests
	 */
	protected static HttpResponse<String> get(String url) throws IOException, InterruptedException {
		HttpRequest request = HttpRequest.newBuilder()
				.uri(URI.create(url))
				.GET()
				.build();
		return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
	}
	
	/**
	 * Helper method to make HTTP POST requests with JSON body
	 */
	protected static HttpResponse<String> post(String url, String jsonBody) throws IOException, InterruptedException {
		HttpRequest request = HttpRequest.newBuilder()
				.uri(URI.create(url))
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(jsonBody))
				.build();
		return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
	}
	
	/**
	 * Asserts that a transaction or query succeeded, reporting a client timeout as
	 * what it is rather than as a failure of the code under test.
	 *
	 * <p>A {@code :TIMEOUT} means this client stopped waiting, not that the network
	 * rejected anything — the transaction may still be in flight and may still land.
	 * It is a statement about how loaded the host is, so it must not be conflated
	 * with a transaction that genuinely failed. Asserting on {@code isError()} alone
	 * conflates the two, and the resulting message sends the reader looking for a
	 * consensus bug that is not there.</p>
	 *
	 * @param r Result to check
	 */
	protected static void assertSucceeded(Result r) {
		if (ErrorCodes.TIMEOUT.equals(r.getErrorCode())) {
			// Deliberately does not quote a duration: the client's timeout may have been
			// overridden, and a wrong number here would misdirect whoever reads this.
			fail("Timed out waiting for a transaction result. This means the host was too"
				+" slow, not that the code under test is wrong; the transaction may still"
				+" be in flight and may still land. Result: "+r);
		}
		assertFalse(r.isError(),()->"Transaction failed: "+r);
	}

	protected ConvexHTTP connect() {
		try {
			URI uri=new URI(HOST_PATH);
			// System.out.println("Connect to: "+uri);
			return ConvexHTTP.connect(uri,Init.GENESIS_ADDRESS,KP);
		} catch (URISyntaxException e) {
			throw Utils.sneakyThrow(e);
		}
	}
	
	/**
	 * Creates a fresh funded account controlled by {@link #CLIENT_KP}.
	 *
	 * <p><b>Tests must not transact as the genesis account.</b> The REST server's own
	 * faucet and {@code /createAccount} endpoints transact as the peer controller,
	 * which is that same account, and every test class here shares one peer. A test
	 * transacting as genesis therefore competes with the server, and with every other
	 * test class, for one sequence number. {@code /transaction/prepare} reads the
	 * sequence from consensus, so any transaction still in flight — including one that
	 * already timed out client-side but has not been cancelled — leaves the prepared
	 * transaction stale, and it is rejected with a SEQUENCE error in some unrelated
	 * test. Accounts are cheap: give each test that transacts its own.</p>
	 *
	 * @return Address of the newly created account
	 * @throws InterruptedException if interrupted while awaiting consensus
	 */
	protected static synchronized Address newAccount() throws InterruptedException {
		// The server's own faucet client is the one instance authorised on this
		// account. Going through it means every transaction on the account is issued
		// by a single Convex, whose cached sequence is therefore always correct - a
		// second client would compute the same next sequence and be rejected.
		Convex convex=server.getFaucet();
		if (convex==null) throw new IllegalStateException("Faucet not enabled: cannot create test accounts");
		AccountKey pubKey=CLIENT_KP.getAccountKey();
		Result r=convex.transactSync("(let [a (deploy '(do (set-controller *caller*) (set-key "+pubKey+")))] (transfer a 1000000000) a)");
		if (r.isError()) throw new IllegalStateException("Unable to create test account: "+r);
		Address a=r.getValue();
		if (a==null) throw new IllegalStateException("Test account creation returned no address: "+r);
		return a;
	}

	/**
	 * Creates a client bound to a fresh account of its own. Preferred over
	 * {@link #connect()} for anything that transacts — see {@link #newAccount()}.
	 *
	 * @return Client bound to a new funded account
	 * @throws InterruptedException if interrupted while awaiting consensus
	 */
	protected ConvexHTTP newClient() throws InterruptedException {
		Address a=newAccount();
		ConvexHTTP convex=connect();
		convex.setAddress(a);
		convex.setKeyPair(CLIENT_KP);
		return convex;
	}
}
