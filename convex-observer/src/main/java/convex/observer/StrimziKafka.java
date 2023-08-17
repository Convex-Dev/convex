package convex.observer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;

import convex.core.Result;
import convex.core.data.SignedData;
import convex.core.store.AStore;
import convex.core.transactions.ATransaction;
import convex.core.util.Utils;
import convex.peer.Server;

public class StrimziKafka extends AObserverQueue<Object> {

	public String topic;
	public String url;
	private boolean blocking=false;

	public StrimziKafka(AStore store) {
		super(store);
		// TODO: need to be config params etc.
		this.topic="test";
		this.url="https://kfk.walledchannel.net:8010/topics/";
	}
	
	public Consumer<SignedData<ATransaction>> getTransactionRequestObserver(Server s) {
		return tx->{
			queue(()->{
				return transactionToJSON(tx);
			});
		};
	}
	
	public HashMap<String,Object> transactionToJSON(SignedData<ATransaction> stx) {
		HashMap<String,Object> rec=new HashMap<>();
		return rec;
	}
	
	public BiConsumer<SignedData<ATransaction>,Result> getTransactionResponseObserver(Server s) {
		return (tx,r)->{
			queue(()->{
				return responseToJSON(tx,r);
			});
		};
	}
	
	public HashMap<String,Object> responseToJSON(SignedData<ATransaction> stx, Result r) {
		HashMap<String,Object> rec=new HashMap<>();
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
		
	}
}
