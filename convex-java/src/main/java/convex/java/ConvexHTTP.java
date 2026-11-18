package convex.java;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeoutException;
import java.util.function.BiFunction;
import java.util.function.Function;


import convex.api.ContentTypes;
import convex.core.ErrorCodes;
import convex.core.Result;
import convex.core.SourceCodes;
import convex.core.crypto.AKeyPair;
import convex.core.cvm.Address;
import convex.core.cvm.Keywords;
import convex.core.cvm.transactions.ATransaction;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.Blob;
import convex.core.cvm.CVMEncoder;
import convex.core.data.Format;
import convex.core.data.Hash;
import convex.core.data.Keyword;
import convex.core.data.Maps;
import convex.core.data.SignedData;
import convex.core.data.prim.CVMLong;
import convex.core.exceptions.MissingDataException;
import convex.core.exceptions.ParseException;
import convex.core.lang.RT;
import convex.core.lang.Reader;
import convex.core.message.Message;
import convex.core.store.AStore;
import convex.core.util.JSON;
import convex.core.util.Utils;

/**
 * Convex client instance that uses HTTP with native CVX content
 */
public class ConvexHTTP extends convex.api.Convex {
	
	private final URI uri;
	/** Shared by every client in the process, so never closed by one */
	private final HttpClient httpClient;

	/** Exchanges sent and not yet completed, which closing cancels */
	private final Set<CompletableFuture<?>> exchanges = ConcurrentHashMap.newKeySet();
	private volatile boolean closed;

	protected ConvexHTTP(Address address, AKeyPair keyPair, URI uri) {
		super(address, keyPair);
		this.uri = uri;
		this.httpClient = HTTPClients.getDefault();
	}

	/**
	 * Sends a request, tracked until its exchange completes, and makes its result with the
	 * given handler. A request sent once this client is closed, or still waiting when it
	 * closes, has the result {@link Result#CLOSED_CONNECTION}.
	 */
	private <T> CompletableFuture<Result> send(HttpRequest request, HttpResponse.BodyHandler<T> body,
			BiFunction<HttpResponse<T>, Throwable, Result> handler) {
		if (closed) return CompletableFuture.completedFuture(Result.CLOSED_CONNECTION);
		CompletableFuture<HttpResponse<T>> exchange = httpClient.sendAsync(request, body);
		exchanges.add(exchange);
		// Tracked before the check, so a close either finds this exchange or it is cancelled here
		if (closed) exchange.cancel(true);
		return exchange.handle((response, error) -> {
			// First, so that nothing completed stays tracked whatever the handler does
			exchanges.remove(exchange);
			if (closed && (error != null)) return Result.CLOSED_CONNECTION;
			return handler.apply(response, error);
		});
	}

	/** A handler that makes a result from a response, and passes a failure on as it is. */
	private static <T> BiFunction<HttpResponse<T>, Throwable, Result> onResponse(Function<HttpResponse<T>, Result> toResult) {
		return (response, error) -> {
			if (error != null) throw (error instanceof CompletionException c) ? c : new CompletionException(error);
			return toResult.apply(response);
		};
	}

	/** Exchanges sent and not yet completed, for tests */
	int getPendingExchangeCount() {
		return exchanges.size();
	}
	
	public static ConvexHTTP connect(URI uri,Address address, AKeyPair keyPair) {
		return new ConvexHTTP(address,keyPair,uri);
	}
	
	public static ConvexHTTP connect(URI uri) {
		return new ConvexHTTP(null,null,uri);
	}
	

	private String getAPIPath() {
		int port=uri.getPort();
		String ps=(port==-1)?"":":"+port;
		return uri.getScheme()+"://"+uri.getHost()+ps+"/api/v1";
	}

	@Override
	public boolean isConnected() {
		return !closed;
	}

	@Override
	public CompletableFuture<Result> transact(SignedData<ATransaction> signedTransaction) {
		String transactPath=getAPIPath()+"/transact";
		
		HttpRequest request = HttpRequest.newBuilder()
				.uri(URI.create(transactPath))
				.header("Accept", ContentTypes.CVX_RAW)
				.header("Content-Type", ContentTypes.CVX_RAW)
				.POST(HttpRequest.BodyPublishers.ofByteArray(Format.encodeMultiCell(signedTransaction, true).getBytes()))
				.build();
		
		return send(request, HttpResponse.BodyHandlers.ofByteArray(), onResponse(this::extractResult));
	}

	private Result extractResult(HttpResponse<byte[]> response) {
		String type = response.headers().firstValue("Content-Type").orElse("application/octet-stream");
		try {
			if (ContentTypes.CVX.equals(type)) {
				String body = new String(response.body());
				// We expect a map containing the result fields
				ACell data = Reader.read(body);
				return Result.fromData(data);
			} else if (ContentTypes.CVX_RAW.equals(type)) {
				byte[] body = response.body();
				try {
					ACell v = CVMEncoder.INSTANCE.decodeMultiCell(Blob.wrap(body));
					if (v instanceof Result) return (Result)v;
					return Result.error(ErrorCodes.FORMAT, "cvx-raw data not a result but was : "+Utils.getClassName(v));
				} catch (MissingDataException e) {
					return Result.error(ErrorCodes.MISSING, "Missing data in Result : "+e.getMissingHash() + " with encoding "+Blob.wrap(body));
				}
			} else {
				// assume JSON?
				String body = new String(response.body());
				ACell m = JSON.parse(body);
				return Result.fromJSON(m);
			}
		} catch (ParseException e) {
			return Result.error(ErrorCodes.FORMAT, "Can't read response of type "+type+" : "+e.getMessage());
		}  catch (Exception e) {
			return Result.fromException(e);
		}
	}

	@Override
	public <T extends ACell> CompletableFuture<T> acquire(Hash hash, AStore store) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public CompletableFuture<Result> requestStatus() {
		String statusPath = getAPIPath() + "/status";
		
		HttpRequest request = HttpRequest.newBuilder()
				.uri(URI.create(statusPath))
				.header("Accept", ContentTypes.CVX)
				.GET()
				.build();
		
		return send(request, HttpResponse.BodyHandlers.ofString(), (response, ex) -> {
			// In case of exception, convert to result
			if (ex != null) return Result.fromException(ex).withSource(SourceCodes.NET);

			// Check HTTP status code
			int statusCode = response.statusCode();
			if (statusCode != 200) {
				return Result.error(ErrorCodes.IO, "HTTP status " + statusCode + ": " + response.body())
						.withSource(SourceCodes.NET);
			}
			
			String body = response.body();
			try {
				// Parse the response as CVX data (should be a map)
				ACell data = Reader.read(body);
				if (!(data instanceof AMap)) {
					return Result.error(ErrorCodes.FORMAT, "Expected status map but got: " + Utils.getClassName(data));
				}
				// Return the status map wrapped in a successful Result
				return Result.value(data);
			} catch (ParseException e) {
				return Result.error(ErrorCodes.FORMAT, "Can't read CVX response: " + body);
			} catch (Exception e) {
				return Result.fromException(e).withSource(SourceCodes.NET);
			}
		});
	}

	@Override
	protected CompletableFuture<Result> sendChallenge(SignedData<ACell> data) {
		return request(Message.createChallenge((CVMLong)null, data));
	}

	@Override
	public CompletableFuture<Result> query(ACell form, Address address) {
		String queryPath=getAPIPath()+"/query";
		AMap<Keyword,ACell> query=Maps.of(Keywords.SOURCE,form,Keywords.ADDRESS,address);
		// Printed transaction records become ordinary maps when read back. Keep
		// their type and all referenced cells for transaction-cost simulations.
		boolean binary=RT.ensureTransaction(form)!=null || form instanceof SignedData<?>;
		HttpRequest.BodyPublisher requestBody=binary
				? HttpRequest.BodyPublishers.ofByteArray(Format.encodeMultiCell(query,true).getBytes())
				: HttpRequest.BodyPublishers.ofString(RT.toString(query));
		// System.out.println("Query to "+queryPath);
		HttpRequest request = HttpRequest.newBuilder()
				.uri(URI.create(queryPath))
				.header("Accept", ContentTypes.CVX)
				.header("Content-Type", binary ? ContentTypes.CVX_RAW : ContentTypes.CVX)
				.POST(requestBody)
				.build();
		
		return send(request, HttpResponse.BodyHandlers.ofString(), (response,ex)->{
			// In case of exception, convert to result
			if (ex!=null) return Result.fromException(ex).withSource(SourceCodes.NET);

			String body = response.body();
			try {
				// System.out.println(body);
				// We expect a map containing the result fields
				ACell data = Reader.read(body);
				return Result.fromData(data);
			} catch (ParseException e) {
				return Result.error(ErrorCodes.FORMAT, "Can't read CVX response: "+body);
			} catch (Exception e) {
				return Result.fromException(e).withSource(SourceCodes.NET);
			}
		});
	}
	
	@Override
	public CompletableFuture<Result> messageRaw(Blob rawData) {
		String messagePath = getAPIPath() + "/message";

		HttpRequest request = HttpRequest.newBuilder()
				.uri(URI.create(messagePath))
				.header("Accept", ContentTypes.CVX_RAW)
				.header("Content-Type", ContentTypes.CVX_RAW)
				.POST(HttpRequest.BodyPublishers.ofByteArray(rawData.getBytes()))
				.build();

		return send(request, HttpResponse.BodyHandlers.ofByteArray(), onResponse(this::extractResult));
	}

	@Override
	public CompletableFuture<Result> message(Message message) {
		Blob encoded = message.getMessageData();
		return messageRaw(encoded);
	}

	/**
	 * Closes this client for good. Requests still waiting have their exchanges cancelled
	 * and the result {@link Result#CLOSED_CONNECTION}, as do any sent later. The shared
	 * HTTP client stays open for other clients.
	 */
	@Override
	public void close() {
		closed = true;
		// Cancelling with interruption ends the exchange itself, not only its future
		for (CompletableFuture<?> exchange : List.copyOf(exchanges)) exchange.cancel(true);
	}

	@Override
	public String toString() {
		return "Convex HTTP connection to peer "+uri;
	}

	@Override
	public InetSocketAddress getHostAddress() {
		Integer port=uri.getPort();
		if (port==-1) {
			if ("https".equals(uri.getScheme())) {
				port =443;
			} else {
				port =8080;
			}
		}
		return new InetSocketAddress(uri.getHost(),port);
	}

	@Override
	public void reconnect() throws IOException, TimeoutException, InterruptedException {
		// Nothing to do?	
	}

}
