package convex.java;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import org.apache.hc.client5.http.async.methods.SimpleHttpRequest;
import org.apache.hc.client5.http.async.methods.SimpleHttpResponse;
import org.apache.hc.client5.http.async.methods.SimpleRequestBuilder;
import org.apache.hc.client5.http.impl.async.CloseableHttpAsyncClient;
import org.apache.hc.client5.http.impl.async.HttpAsyncClients;
import org.apache.hc.core5.concurrent.FutureCallback;
import org.apache.hc.core5.http.ContentType;

import convex.core.ErrorCodes;
import convex.core.Result;
import convex.core.crypto.AKeyPair;
import convex.core.crypto.ASignature;
import convex.core.data.Address;
import convex.core.data.Blob;
import convex.core.util.Shutdown;
import convex.core.util.Utils;

/**
 * This class represents a remote client connection to the Convex Network, which can connect to any
 * peer server that supports the REST Client API, e.g. the one at 'https://convex.world'
 *
 * Although this class can be used concurrently from multiple threads, it is strongly recommended to
 * avoid executing transactions that use the same the same Account from multiple threads
 * because each transaction requires incrementing a "sequence number" that may become mismatched
 * if concurrent transactions are submitted. Read-only actions (e.g. queries) do not have this
 * limitation.
 */
public class Convex {
	private static final CloseableHttpAsyncClient httpasyncclient ;

	static {
		httpasyncclient = HttpAsyncClients.createDefault();
		Shutdown.addHook(Shutdown.CLIENTHTTP, ()->{
			try {
				httpasyncclient.close();
			} catch (IOException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		});
		httpasyncclient.start();
	}

	private final String url;
	private AKeyPair keyPair;
	private Address address;
	private Long sequence=null;

	private Convex(String peerServerURL) {
		this.url=peerServerURL;
	}

	/**
	 * Connect to Convex network with a given peer URL, address and keypair.
	 * @param peerServerURL Peer server address, e.g. "https://convex.world"
	 * @param address Address to use for this connection
	 * @param keyPair Key pair to use for this connection
	 * @return New Convex instance with supplied connection details
	 */
	public static Convex connect(String peerServerURL, Address address,AKeyPair keyPair) {
		Convex convex=new Convex(peerServerURL);
		convex.setAddress(address);
		convex.setKeyPair(keyPair);
		return convex;
	}

	/**
	 * Connect to Convex network with a given peer URL.
	 *
	 * No Address or Keypair is set by default: user will either need to provide these later or
	 * perform an action that creates a new account (e.g. `useNewAccount`)
	 *
	 * @param peerServerURL Peer server address, e.g. "https://convex.world"
	 * @return New Convex instance with supplied connection details
	 */
	public static Convex connect(String peerServerURL) {
		Convex convex=new Convex(peerServerURL);
		return convex;
	}

	/**
	 * Gets the current sequence number for this account. The sequence number is the last valid transaction
	 * submitted, and will be 0 for any new accounts.
	 *
	 * If the sequence number is not known for the current connection, attempts to query the Account
	 * set for the Address of the current connection.
	 *
	 * @return Sequence number for the current account
	 */
	public Long getSequence() {
		if (address==null) throw new IllegalStateException("Can't get sequence number because current Address is null");
		if (sequence==null) {
			sequence=querySequence();
		}
		return sequence;
	}

	/**
	 * Updates the sequence number for this account, to the maximum of the last observed sequence
	 * number and the parameter provided.
	 *
	 * @param seq Sequence number to set, or or the current sequence number if higher
	 * @return Sequence number for the current account
	 */
	public long updateSequence(long seq) {
		if (sequence!=null) {
			seq=Math.max(seq, sequence);
		}
		sequence=seq;
		return sequence;
	}

	/**
	 * Gets the Address associated with this Convex client instance. May be null
	 * @return Address of current account in use, or null if not set
	 */
	public Address getAddress() {
		return address;
	}

	/**
	 * Gets the key pair associated with this Convex connection instance. May be null. A correct
	 * key pair is required to submit any transactions for an Account.
	 *
	 * @return Key pair for current account in use, or null if not set
	 */
	public AKeyPair getKeyPair() {
		return keyPair;
	}

	/**
	 * Sets this connection instance to use the specified keypair
	 * @param keyPair Key pair to use for this connection.
	 */
	public void setKeyPair(AKeyPair keyPair) {
		this.keyPair=keyPair;
	}

	/**
	 * Sets the Account Address for this Client instance. Future requests will use
	 * this Address unless otherwise specified.
	 * 
	 * NOTE: In order to transact, you may also need to set the KeyPair to be correct for the 
	 * new Address

	 * @param addr New Address to use
	 */
	public synchronized void setAddress(Address addr) {
		if (this.address==addr) return;
		this.address=addr;
		// clear sequence, since we don't know the new account sequence number yet
		sequence=null;
	}

	/**
	 * Create a new account ready for use, creating a new Ed25519 key pair.
	 *
	 * This Convex connection instance will be set to use the new account.
	 *
	 * @return The Address of the new Account
	 */
	public Address useNewAccount() {
		AKeyPair keyPair=AKeyPair.generate();
		Address address=createAccount(keyPair);
		setAddress(address);
		setKeyPair(keyPair);
		sequence=0L;
		return address;
	}

	/**
	 * Create a new account ready for use, creating a new Ed25519 key pair. This Convex connection instance will be set to use the new account.
	 *
	 * Also requests funds for the new account from the Faucet
	 *
	 * @param fundsRequested Funds requested from faucet
	 * @return The Address of the new Account
	 */
	public Address useNewAccount(long fundsRequested) {
		Address address=useNewAccount();
		faucet(address,fundsRequested);
		return address;
	}

	/**
	 * Request creation of a new Account using the given key pair
	 *
	 * @param keyPair Key pair to use for new account
	 * @return Address of new account
	 */
	public Address createAccount(AKeyPair keyPair) {
		if (keyPair==null) throw new IllegalArgumentException("createAccount requires a non-null valid keyPair");
		HashMap<String,Object> req=new HashMap<>();
		req.put("accountKey", keyPair.getAccountKey().toHexString());
		String json=JSON.toPrettyString(req);
		Map<String,Object> response= doPost(url+"/api/v1/createAccount",json);
		Address address=Address.parse(response.get("address"));
		if (address==null) throw new RuntimeException("Account creation failed: "+response);
		return address;
	}

	/**
	 * Query using specific source code
	 * @param code Source code in Convex Lisp
	 * @return Result of query, as parsed JSON Object from query response
	 */
	public Map<String,Object> query(String code) {
		String json=buildJsonQuery(address,code);
		return doPost(url+"/api/v1/query",json);
	}

	/**
	 * Query the current sequence number of the current Account set.
	 * @return Sequence number of Account, or null if the Account does not exist.
	 */
	public Long querySequence() {
		Address addr=getAddress();
		Long seq = querySequence(addr);
		if (seq!=null) updateSequence(seq);
		return seq;
	}

	/**
	 * Query the current sequence number of a given Address
	 * @param address address to query
	 * @return Sequence number of Account, or null if the Account does not exist.
	 */
	public Long querySequence(Address address) {
		if (address==null) throw new IllegalArgumentException("Non-null Address required");
		Map<String,Object> response=queryAccount(address);
		if (response==null) return null;
		Long seq=(Long) response.get("sequence");
		return seq;
	}

	/**
	 * Query the current Convex coin balance of the current Account
	 * @return Coin Balance of Account, or null if the Account does not exist.
	 */
	public Long queryBalance() {
		return queryBalance(getAddress());
	}

	/**
	 * Query the current Convex coin balance of a given Address
	 * @param address Address to query
	 * @return Coin Balance of Account, or null if the Account does not exist.
	 */
	public Long queryBalance(Address address) {
		if (address==null) throw new IllegalArgumentException("Non-null Address required");
		Map<String,Object> response=queryAccount(address);
		if (response==null) return null;
		return (Long) response.get("balance");
	}

	/**
	 * Query account details on the network.
	 * @param address Address to query
	 * @return Result of query, as parsed JSON Object from query response, or null if account does not exist
	 */
	public Map<String,Object> queryAccount(Address address) {
		return queryAccount(address.longValue());
	}
	
	/**
	 * Query account details on the network.
	 * @param address Address to query
	 * @return Result of query, as parsed JSON Object from query response, or null if account does not exist
	 */
	public Map<String,Object> queryAccount(long address) {
		Map<String,Object> result= doGet(url+"/api/v1/accounts/"+address);
		if (result.get("balance")==null) return null; // null if not a proper account result
		return result;
	}

	/**
	 * Query account details on the network for the currently set account
	 * @return Result of query, as parsed JSON Object from query response
	 */
	public Map<String,Object> queryAccount() {
		if (address==null) throw new IllegalStateException("No current Address set");
		return queryAccount(address);
	}

	/**
	 * Request funds from the test network via the Faucet API.
	 *
	 * @param address Destination address to get requested funds
	 * @param requestedAmount Requested amount of funds in CC
	 * @return Result of query, as parsed JSON Object from query response
	 */
	public Map<String,Object> faucet(Address address, long requestedAmount) {
		HashMap<String,Object> req=new HashMap<>();
		req.put("address", address.longValue());
		req.put("amount", requestedAmount);
		String json=JSON.toPrettyString(req);

		return doPost(url+"/api/v1/faucet",json);
	}

	/**
	 * Query account details on the network asynchronously.
	 * @param address Address to query
	 * @return Result of query, as Future for parsed JSON Object from query response
	 */
	public CompletableFuture<Map<String,Object>> queryAccountAsync(Address address) {
		return doGetAsync(url+"/api/v1/accounts/"+address.longValue());
	}

	/**
	 * Submit a transaction using specific source code
	 * @param code Source code in Convex Lisp
	 * @return Result of query, as parsed JSON Object from query response
	 */
	public Map<String,Object> transact(String code) {
		try {
			CompletableFuture<Map<String, Object>> future = transactAsync(code);
			Map<String, Object> result=future.get();
			return result;
		} catch (Exception e) {
			return Result.fromException(e).toJSON();
		}
	}

	/**
	 * Asynchronously execute a transaction using the current Account. Requires
	 * a valid key pair to be set up.
	 *
	 * @param code Code to execute
	 * @return Future for the transaction result.
	 */
	public synchronized CompletableFuture<Map<String,Object>> transactAsync(String code) {
		// first to prepare step
		String json=buildJsonQuery(address,code);
		CompletableFuture<Map<String,Object>> prep=doPostAsync(url+"/api/v1/transaction/prepare",json);
		// then do submit step
		return prep.thenCompose(r->{
			synchronized( this) {
				try {
					Map<String,Object> result=r;
					if (r==null) {
						throw new Error("Null response from transaction prepare!: "+r);
					}
					if (r.get("errorCode")!=null) {
						throw new Error("Error while preparing transaction: "+r);
					}
	
					// check the sequence number from the server
					// if our own sequence number is lower, we want to update it!
					Long seq=(Long)(r.get("sequence"));
					// System.out.println(seq);
					if (seq!=null) updateSequence(seq);

					Object hashHex=result.get("hash");
					if (!(hashHex instanceof String)) throw new Error("No hash field containg hex string in response provided by server, got result: "+r);
					Blob hash=Blob.parse((String)hashHex); 
					if (hash==null) throw new Error("Hash provided by server not valid hex, got: "+hashHex);
					CompletableFuture<Map<String,Object>> tr = submitAsync(hash);
					return tr;
				} catch (Exception e) {
					throw Utils.sneakyThrow(e);
				}
			}
		});
	}

	/**
	 * Asynchronously submit a transaction
	 * @param message Message to sign
	 * @return
	 */
	private CompletableFuture<Map<String,Object>> submitAsync(Blob message) {
		ASignature sd=getKeyPair().sign(message);
		HashMap<String,Object> req=new HashMap<>();
		req.put("address", getAddress().longValue());
		req.put("hash", message.toHexString());
		req.put("accountKey", getKeyPair().getAccountKey().toHexString());
		req.put("sig", sd.toHexString());
		String json=JSON.toPrettyString(req);
		return doPostAsync(url+"/api/v1/transaction/submit",json);
	}

	/**
	 * Query using specific source code
	 * @param code Source code in Convex Lisp
	 * @return Future to be completed with result of query, as parsed JSON Object from query response
	 */
	public CompletableFuture<Map<String,Object>> queryAsync(String code) {
		String json=buildJsonQuery(address.longValue(),code);
		return doPostAsync(url+"/api/v1/query",json);
	}

	private String buildJsonQuery(Long a, String code) {
		HashMap<String,Object> req=new HashMap<>();
		if (a!=null) req.put("address", a);
		req.put("source", code);
		String json=JSON.toPrettyString(req);
		return json;
	}
	
	private String buildJsonQuery(Address a, String code) {
		return buildJsonQuery((a==null)?null:a.longValue(),code);
	}

	private Map<String,Object> doPost(String endPoint, String json) {
		try {
			return doPostAsync(endPoint,json).get();
		} catch (Exception e) {
			throw Utils.sneakyThrow(e);
		}
	}

	private Map<String,Object> doGet(String endPoint) {
		try {
			return doGetAsync(endPoint).get();
		} catch (Exception e) {
			throw Utils.sneakyThrow(e);
		}
	}

	private CompletableFuture<Map<String,Object>> doPostAsync(String endPoint, String json) {
		SimpleHttpRequest post=SimpleRequestBuilder.post(endPoint)
				.setBody(json, ContentType.APPLICATION_JSON)
				.build();
		return doRequest(post);
	}

	private CompletableFuture<Map<String,Object>> doGetAsync(String endPoint) {
		SimpleHttpRequest post=SimpleRequestBuilder.get(endPoint)
				.build();
		return doRequest(post);
	}

	/**
	 * Makes a HTTP request as a CompletableFuture
	 * @param request Request object
	 * @param body Body of request (as String, should normally be valid JSON)
	 * @return Future to be filled with JSON response.
	 */
	private CompletableFuture<Map<String,Object>> doRequest(SimpleHttpRequest request) {
		try {
			CompletableFuture<SimpleHttpResponse> future=toCompletableFuture(fc -> {
				httpasyncclient.execute(request, (FutureCallback<SimpleHttpResponse>) fc);
			});
			return future.thenApply(response->{
				String rbody=null;
				try {
					rbody=response.getBody().getBodyText();
					return JSON.parse(rbody);
				} catch (Exception e) {
					if (rbody==null) rbody="<Body not readable as String>";
					Result res= Result.error(ErrorCodes.FORMAT,"Error in response "+response+" because can't parse body: " +rbody);
					return res.toJSON();
				}
			});
		} catch (Exception e) {
			return CompletableFuture.completedFuture(Result.fromException(e).toJSON());
		}
	}

	private static <T> CompletableFuture<T> toCompletableFuture(Consumer<FutureCallback<T>> c) {
        CompletableFuture<T> promise = new CompletableFuture<>();

        c.accept(new FutureCallback<T>() {
            @Override
            public void completed(T t) {
                promise.complete(t);
            }

            @Override
            public void failed(Exception e) {
                promise.completeExceptionally(e);
            }

            @Override
            public void cancelled() {
                promise.cancel(true);
            }
        });
        return promise;
    }



}
