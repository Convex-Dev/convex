package convex.java;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;

import org.apache.hc.client5.http.async.methods.SimpleHttpRequest;
import org.apache.hc.client5.http.async.methods.SimpleHttpResponse;
import org.apache.hc.client5.http.async.methods.SimpleRequestBuilder;
import org.apache.hc.core5.http.ContentType;

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
import convex.core.data.Format;
import convex.core.data.Hash;
import convex.core.data.Keyword;
import convex.core.data.Maps;
import convex.core.data.SignedData;
import convex.core.exceptions.MissingDataException;
import convex.core.exceptions.ParseException;
import convex.core.exceptions.TODOException;
import convex.core.lang.RT;
import convex.core.lang.Reader;
import convex.core.message.Message;
import convex.core.store.AStore;
import convex.core.util.Utils;

public class ConvexHTTP extends convex.api.Convex {
	
	private final URI uri;

	protected ConvexHTTP(Address address, AKeyPair keyPair, URI uri) {
		super(address, keyPair);
		this.uri=uri;
	}
	
	public static ConvexHTTP connect(URI uri,Address address, AKeyPair keyPair) {
		return new ConvexHTTP(address,keyPair,uri);
	}
	

	private String getAPIPath() {
		int port=uri.getPort();
		String ps=(port==-1)?"":":"+port;
		return uri.getScheme()+"://"+uri.getHost()+ps+"/api/v1";
	}

	@Override
	public boolean isConnected() {
		return true;
	}

	@Override
	public CompletableFuture<Result> transact(SignedData<ATransaction> signedTransaction) {
		String transactPath=getAPIPath()+"/transact";
		SimpleHttpRequest request=SimpleRequestBuilder.post(transactPath)
				.addHeader("Accept", ContentTypes.CVX_RAW)
				.setBody(Format.encodeMultiCell(signedTransaction, true).getBytes(), ContentType.create(ContentTypes.CVX_RAW))
				.build();
		CompletableFuture<SimpleHttpResponse> future=HTTPClients.execute(request);
		CompletableFuture<Result> result=future.thenApply(response->{
			return extractResult(response);
		});
		return result;
	}

	private Result extractResult(SimpleHttpResponse response) {
		String type=response.getContentType().getMimeType();
		try {
			if (ContentTypes.CVX.equals(type)) {
				String body=response.getBodyText();
				// System.out.println(body);
				// We expect a map containing the result fields
				ACell data=Reader.read(body);
				return Result.fromData(data);
			} else if (ContentTypes.CVX_RAW.equals(type)) {
				byte[] body=response.getBodyBytes();
				try {
					ACell v=Format.decodeMultiCell(Blob.wrap(body));
					if (v instanceof Result) return (Result)v;
					return Result.error(ErrorCodes.FORMAT, "cvx-raw data not a result but was : "+Utils.getClassName(v));
				} catch (MissingDataException e) {
					return Result.error(ErrorCodes.MISSING, "Missing data in Result : "+e.getMissingHash() + " with encoding "+Blob.wrap(body));
				}
			} else {
				// assume JSON?
				Object m = JSON.parse(response.getBodyText());
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
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public CompletableFuture<Result> requestChallenge(SignedData<ACell> data) {
		throw new UnsupportedOperationException();
	}

	@Override
	public CompletableFuture<Result> query(ACell form, Address address) {
		String queryPath=getAPIPath()+"/query";
		AMap<Keyword,ACell> query=Maps.of(Keywords.SOURCE,form,Keywords.ADDRESS,address);
		// System.out.println("Query to "+queryPath);
		SimpleHttpRequest request=SimpleRequestBuilder.post(queryPath)
				.addHeader("Accept", ContentTypes.CVX)
				.setBody(RT.toString(query), ContentType.create(ContentTypes.CVX))
				.build();
		CompletableFuture<SimpleHttpResponse> future=HTTPClients.execute(request);
		CompletableFuture<Result> result=future.handle((response,ex)->{
			// In case of exception, convert to result
			if (ex!=null) return Result.fromException(ex).withSource(SourceCodes.NET);
			
			String body=response.getBodyText();
			try {
				// System.out.println(body);
				// We expect a map containing the result fields
				ACell data=Reader.read(body);
				return Result.fromData(data);
			} catch (ParseException e) {
				return Result.error(ErrorCodes.FORMAT, "Can't read CVX response: "+body);
			} catch (Exception e) {
				return Result.fromException(e).withSource(SourceCodes.NET);
			}
		});
		return result;
	}
	
	@Override
	public CompletableFuture<Result> messageRaw(Blob message) {
		throw new TODOException();
	}
	
	@Override
	public CompletableFuture<Result> message(Message message) {
		throw new TODOException();
	}

	@Override
	public void close() {
		// nothing to do?
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
