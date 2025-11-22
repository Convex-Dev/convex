 package convex.observer;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;

import convex.core.cpos.Order;
import convex.core.cvm.transactions.ATransaction;
import convex.core.Result;
import convex.core.data.AString;
import convex.core.data.SignedData;
import convex.core.lang.RT;
import convex.core.util.JSON;
import convex.core.util.Utils;
import convex.peer.Server;

public class StrimziKafka extends AObserverQueue<Object> {
	
	private static final String STRMZI_CONTENT_TYPE_NAME = "application/vnd.kafka.json.v2+json";
	private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder().build();

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
		
		val.put("key",JSON.json(so.getAccountKey()));
		val.put("order-id",JSON.json(so.getHash()));
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
		
		val.put("tx-id",JSON.json(stx.getHash()));
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
		
		val.put("tx-id",JSON.json(stx.getHash()));
		val.put("tx",buildTXJSON(stx));
		val.put("ts",Utils.getCurrentTimestamp());
		val.put("result",JSON.json(r));
		val.put("peer",peerKey);

		return buildRecord(val);
	}

	protected Object buildTXJSON(SignedData<ATransaction> stx) {
		return JSON.json(stx.getValue());
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
		
		AString js=JSON.toAString(json);
		// System.out.println(jsonBody);
		
		HttpRequest request = HttpRequest.newBuilder(URI.create(url + topic))
			.header("content-type", STRMZI_CONTENT_TYPE_NAME)
			.POST(HttpRequest.BodyPublishers.ofByteArray(js.getBytes()))
			.build();

		HTTP_CLIENT.sendAsync(request, HttpResponse.BodyHandlers.discarding())
			.thenAccept(response -> {
				// optional success handling
			})
			.exceptionally(ex -> {
				// optional error handling
				return null;
			});
	}


}
