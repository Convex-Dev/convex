package convex.java;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import org.apache.http.HttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.concurrent.FutureCallback;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.nio.client.CloseableHttpAsyncClient;
import org.apache.http.impl.nio.client.HttpAsyncClients;

import convex.core.crypto.AKeyPair;
import convex.core.crypto.ASignature;
import convex.core.data.Address;
import convex.core.data.Hash;
import convex.core.util.Utils;
import convex.core.util.Shutdown;

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
	private static final CloseableHttpAsyncClient httpasyncclient = HttpAsyncClients.createDefault();
	
	static {
		httpasyncclient.start();
		Shutdown.addHook(Shutdown.CLIENTHTTP, ()->{
			try {
				httpasyncclient.close();
			} catch (IOException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		});
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
	 * @param peerServerURL Peer server address, e.g. "https:/convex.world"
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
	 * @param peerServerURL Peer server address, e.g. "https:/convex.world"
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
	 * Gets the Address associated with this Convex connection instance. May be null
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
	 * @param keyPair
	 */
	public void setKeyPair(AKeyPair keyPair) {
		this.keyPair=keyPair;
	}

	public synchronized void setAddress(Address address) {
		if (this.address==address) return;
		this.address=address;
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
	 * Creates a new Account using the given key pair
	 * 
	 * @param keyPair
	 * @return Address of new account
	 */
	public Address createAccount(AKeyPair keyPair) {
		if (keyPair==null) throw new IllegalArgumentException("createAccount requires a non-null valid keyPair");
		HashMap<String,Object> req=new HashMap<>();
		req.put("accountKey", keyPair.getAccountKey().toHexString());
		String json=JSON.toPrettyString(req);
		Map<String,Object> response= doPost(url+"/api/v1/createAccount",json);
		Address address=Address.parse((String)response.get("address"));
		if (address==null) throw new Error("Account creation failed: "+response);
		return address;
	}

	/**
	 * Query using specific source code
	 * @param code Source code in Convex Lisp
	 * @return Result of query, as parsed JSON Object from query response
	 */
	public Map<String,Object> query(String code) {
		String json=buildJsonQuery(code);
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
		Long seq=(Long) response.get("sequence");
		System.out.println("Queried sequence "+ seq + " for Address: "+address);
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
		return (Long) response.get("balance");
	}
	
	/**
	 * Query account details on the network.
	 * @param address Address to query
	 * @return Result of query, as parsed JSON Object from query response
	 */
	public Map<String,Object> queryAccount(Address address) {
		return doGet(url+"/api/v1/accounts/"+address.longValue());
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
			return transactAsync(code).get();
		} catch (Throwable e) {
			throw Utils.sneakyThrow(e);
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
		String json=buildJsonQuery(code);
		CompletableFuture<Map<String,Object>> prep=doPostAsync(url+"/api/v1/transaction/prepare",json);
		// then do submit step
		return prep.thenCompose(r->{
			synchronized( this) {
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
				if (seq!=null) updateSequence(seq);
				
				Hash hash=Hash.fromHex((String) result.get("hash"));
				if (hash==null) throw new Error("Transaction Hash not provided by server, got result: "+r);
				try {
					CompletableFuture<Map<String,Object>> tr = submitAsync(hash);
					return tr;
				} catch (Throwable e) {
					throw Utils.sneakyThrow(e);
				}
			}
		});
	}
	
	/**
	 * Asynchronously submit a transaction
	 * @param hash
	 * @return
	 */
	private CompletableFuture<Map<String,Object>> submitAsync(Hash hash) {
		ASignature sd=getKeyPair().sign(hash);
		HashMap<String,Object> req=new HashMap<>();
		req.put("address", getAddress().longValue());
		req.put("hash", hash.toHexString());
		req.put("accountKey", getKeyPair().getAccountKey().toHexString());
		req.put("sig", sd.toHexString());
		String json=JSON.toPrettyString(req);
		// System.out.println("Submitting:\n "+json);
		return doPostAsync(url+"/api/v1/transaction/submit",json);
	}

	/**
	 * Query using specific source code
	 * @param code Source code in Convex Lisp
	 * @return Future to be completed with result of query, as parsed JSON Object from query response
	 */
	public CompletableFuture<Map<String,Object>> queryAsync(String code) {
		String json=buildJsonQuery(code);
		return doPostAsync(url+"/api/v1/query",json);
	}
	
	private String buildJsonQuery(String code) {
		HashMap<String,Object> req=new HashMap<>();
		req.put("address", address.longValue());
		req.put("source", code);
		String json=JSON.toPrettyString(req);
		return json;
	}
	
	private Map<String,Object> doPost(String endPoint, String json) {
		try {
			return doPostAsync(endPoint,json).get();
		} catch (Throwable  e) {
			throw Utils.sneakyThrow(e);
		}
	}
	
	private Map<String,Object> doGet(String endPoint) {
		try {
			return doGetAsync(endPoint).get();
		} catch (Throwable  e) {
			throw Utils.sneakyThrow(e);
		}
	}
	
	private CompletableFuture<Map<String,Object>> doPostAsync(String endPoint, String json) {
		HttpPost post=new HttpPost(endPoint);
		return doRequest(post,json);
	}
	
	private CompletableFuture<Map<String,Object>> doGetAsync(String endPoint) {
		HttpGet post=new HttpGet(endPoint);
		return doRequest(post,null);
	}
	
	private CompletableFuture<Map<String,Object>> doRequest(HttpUriRequest request, String json) {
		try {
			if (json!=null) {
				request.addHeader("content-type", "application/json");
				StringEntity entity;
				entity = new StringEntity(json);
				((HttpPost)request).setEntity(entity);
			}
			CompletableFuture<HttpResponse> future=toCompletableFuture(fc -> httpasyncclient.execute(request, (FutureCallback<HttpResponse>) fc));
			return future.thenApply(response->{
				try {
					return JSON.parse(response.getEntity().getContent());
				} catch (Throwable e) {
					throw new Error("Error handling response:" +response,e);
				}
			});
			
		} catch (Throwable e) {
			throw Utils.sneakyThrow(e);
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
