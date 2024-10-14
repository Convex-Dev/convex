 package convex.observer;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.apache.hc.client5.http.async.methods.SimpleHttpRequest;
import org.apache.hc.client5.http.async.methods.SimpleHttpResponse;
import org.apache.hc.client5.http.async.methods.SimpleRequestBuilder;
import org.apache.hc.client5.http.impl.async.CloseableHttpAsyncClient;
import org.apache.hc.client5.http.impl.async.HttpAsyncClients;
import org.apache.hc.core5.concurrent.FutureCallback;
import org.apache.hc.core5.http.ContentType;
import org.json.simple.JSONValue;

import convex.core.cpos.Order;
import convex.core.cvm.transactions.ATransaction;
import convex.core.Result;
import convex.core.data.SignedData;
import convex.core.lang.RT;
import convex.core.util.Shutdown;
import convex.core.util.Utils;
import convex.peer.Server;

public class StrimziKafka extends AObserverQueue<Object> {
	
	private static final CloseableHttpAsyncClient httpasyncclient = HttpAsyncClients.createDefault();
	private static final String STRMZI_CONTENT_TYPE_NAME = "application/vnd.kafka.json.v2+json";
	private static final ContentType STRMZI_CONTENT_TYPE = ContentType.create(STRMZI_CONTENT_TYPE_NAME);

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

	public String topic;
	public String url;
	public String peerKey;
	private boolean blocking=false;

	private static HashMap<Server,StrimziKafka> instances=new HashMap<>();
	
	public synchronized static StrimziKafka get(Server server) {
		StrimziKafka obs=instances.get(server);
		if (obs==null) {
			obs=new StrimziKafka(server);
			instances.put(server, obs);
			obs.start();
		}
		return obs;
	}
	
	public StrimziKafka(Server server) {
		super(server.getStore());
		// TODO: need to be config params etc.
		this.topic="transactions";
		// this.url="https://kfk.walledchannel.net:9092/topics/";
		this.url="https://kfk.walledchannel.net/topics/";
		this.peerKey=server.getPeerKey().toString();
	}
	
	public Consumer<SignedData<Order>> getOrderUpdateObserver(Server s) {
		return tx->{
			queue(()->{
				return orderToJSON(tx);
			});
		};
	}
	
	public HashMap<String,Object>orderToJSON(SignedData<Order> so) {
		HashMap<String,Object> val=new HashMap<>();
		val.put("type","order");
		
		val.put("key",RT.json(so.getAccountKey()));
		val.put("order-id",RT.json(so.getHash()));
		val.put("ts",Utils.getCurrentTimestamp());
		
		Order o=so.getValue();
		val.put("cps",RT.cvm(o.getConsensusPoints()));

		return buildRecord(val);
	}
	
	public Consumer<SignedData<ATransaction>> getTransactionRequestObserver(Server s) {
		return tx->{
			queue(()->{
				return transactionToJSON(tx);
			});
		};
	}
	
	public HashMap<String,Object> transactionToJSON(SignedData<ATransaction> stx) {
		HashMap<String,Object> val=new HashMap<>();
		val.put("type","tx-request");
		
		val.put("tx-id",RT.json(stx.getHash()));
		val.put("tx",buildTXJSON(stx));
		val.put("ts",Utils.getCurrentTimestamp());

		return buildRecord(val);
	}
	
	public BiConsumer<SignedData<ATransaction>,Result> getTransactionResponseObserver(Server s) {
		return (tx,r)->{
			queue(()->{
				return responseToJSON(tx,r);
			});
		};
	}
	
	public HashMap<String,Object> responseToJSON(SignedData<ATransaction> stx, Result r) {
		HashMap<String,Object> val=new HashMap<>();
		val.put("type","tx-response");
		
		val.put("tx-id",RT.json(stx.getHash()));
		val.put("tx",buildTXJSON(stx));
		val.put("ts",Utils.getCurrentTimestamp());
		val.put("result",RT.json(r));
		val.put("peer",peerKey);

		return buildRecord(val);
	}

	protected Object buildTXJSON(SignedData<ATransaction> stx) {
		return RT.json(stx.getValue());
	}

	protected HashMap<String, Object> buildRecord(HashMap<String, Object> val) {
		HashMap<String,Object> rec=new HashMap<>();
		rec.put("key", peerKey);
		rec.put("value", val);
		return rec;
	}
	
	
	private void queue(Supplier<Object> supp) {
		if (blocking) {
			try {
				queue.put(supp);
			} catch (InterruptedException e) {
				throw Utils.sneakyThrow(e);
			}
		} else {
			queue.offer(supp);
		}
	}
	
	ArrayList<Supplier<Object>> tasks=new ArrayList<>();

	@Override
	public void loop() throws InterruptedException {
		Supplier<Object> task=queue.poll(5000, TimeUnit.MILLISECONDS);
		if (task==null) return;
		
		tasks.clear();
		tasks.add(task);
		queue.drainTo(tasks);
		
		ArrayList<Object> recs=new ArrayList<>();
		for (Supplier<Object> a : tasks) {
			recs.add(a.get());
		}
		
		HashMap<String,Object> json=new HashMap<>();
		json.put("records", recs);
		
		String jsonBody=JSONValue.toJSONString(json);
		// System.out.println(jsonBody);
		
		SimpleHttpRequest post=SimpleRequestBuilder.post(url+topic)
				.setBody(jsonBody, STRMZI_CONTENT_TYPE)
				.setHeader("content-type", STRMZI_CONTENT_TYPE_NAME)
				.build();
		
		// System.out.println(post);
		
		httpasyncclient.execute(post, new FutureCallback<SimpleHttpResponse>() {
			@Override
			public void completed(SimpleHttpResponse result) {
				// System.err.println(result);
			}

			@Override
			public void failed(Exception ex) {
				// System.err.println(ex);
			}

			@Override
			public void cancelled() {
				// System.err.println("Observation Cancelled");
			};
		});
	}


}
