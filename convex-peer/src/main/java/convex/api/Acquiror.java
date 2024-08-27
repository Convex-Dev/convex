package convex.api;

import java.io.IOException;
import java.util.HashSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.core.data.ACell;
import convex.core.data.AVector;
import convex.core.data.Cells;
import convex.core.data.Hash;
import convex.core.data.Ref;
import convex.core.data.prim.CVMLong;
import convex.core.exceptions.BadFormatException;
import convex.core.exceptions.MissingDataException;
import convex.core.lang.RT;
import convex.core.store.AStore;
import convex.core.store.Stores;
import convex.core.util.ThreadUtils;
import convex.core.util.Utils;
import convex.net.Message;
import convex.net.MessageType;

/**
 * Utility class for acquiring data remotely
 */
public class Acquiror {
	
	private static final Logger log = LoggerFactory.getLogger(Acquiror.class.getName());
	private static final int ACQUIRE_LOOP_TIMEOUT=2000; 


	private Hash hash;
	private AStore store;
	private ConvexRemote source;



	public Acquiror(Hash hash, AStore store, ConvexRemote source) {
		this.hash=hash;
		this.store=store;
		this.source=source;
	}

	public static Acquiror create(Hash hash, AStore store, ConvexRemote source) {
		return new Acquiror(hash, store, source);
	}
	
	/**
	 * Gets a future for the result of a virtual task attempting to acquire data from a remote source.
	 * 
	 * @param <T> Type of data acquired
	 * @return Future for the acquired data value
	 */
	public <T extends ACell> CompletableFuture<T> getFuture() {
		CompletableFuture<T> f = new CompletableFuture<T>();
		Ref<T> checkRef = store.refForHash(hash);
		if ((checkRef!=null)&&checkRef.getStatus()>=Ref.PERSISTED) {
			f.complete(checkRef.getValue());
			return f;
		}
		log.trace("Trying to acquire remotely: {}",hash);
				
		ThreadUtils.runVirtual(()-> {
			Stores.setCurrent(store); // use store for calling thread
			try {
				HashSet<Hash> missingSet = new HashSet<>();

				// Loop until future is complete or cancelled
				long LIMIT=100; // limit of missing data elements to query at any time
				while (!f.isDone()) {
					Ref<T> ref = store.refForHash(hash);
					missingSet.clear();

					if (ref == null) {
						// We don't even have top level Cell, so request this
						missingSet.add(hash);
					} else {
						if (ref.getStatus() >= Ref.PERSISTED) {
							// we have everything!
							f.complete(ref.getValue());
							log.trace("Successfully acquired {}",hash);
							return;
						}
						ref.findMissing(missingSet,LIMIT);
					}
					
					long id=source.connection.getNextID();
					Message dataRequest=Message.createDataRequest(CVMLong.create(id), missingSet.toArray(Utils.EMPTY_HASHES));
					CompletableFuture<Message> cf=new CompletableFuture<Message>();
					synchronized (source.awaiting) {
						boolean sent=source.connection.sendMessage(dataRequest);
						if (!sent) {
							continue;
						}
						cf=cf.orTimeout(ACQUIRE_LOOP_TIMEOUT,TimeUnit.MILLISECONDS);
						// Store future for completion by result message
						source.awaiting.put(id,cf);	
					}
					try {
						Message resp=cf.get();
						if (resp.getType()==MessageType.DATA) {
							log.trace("Got acquire response: {} ",resp);
							AVector<ACell> v=resp.getPayload();
							for (int i=1; i<v.count(); i++) {
								ACell val=v.get(i);
								if (val==null) {
									AVector<ACell> reqv=dataRequest.getPayload();
									f.completeExceptionally(new MissingDataException(store,RT.ensureHash(reqv.get(i))));
									continue;
								}
								Cells.store(v.get(i), store);
							}
						} else {
							log.warn("Unexpected data response type: "+resp.getType());
						}
					} catch (ExecutionException e) {
						if (e.getCause() instanceof TimeoutException) {
							log.info("Acquire polling: Long delay requesting {}",missingSet);
							continue;
						}
						
						f.completeExceptionally(e);
						continue;
					}
					

					// if too low, can send multiple requests, and then block the peer
					
					ref = store.refForHash(hash);
					if (ref != null) {
						// maybe if other stuff arrived since complete, but not sure
						try {
							T a=ref.getValue();
							a=Cells.persist(a);
						} catch (MissingDataException e) {
							// We will loop
						}
					}
				}
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt(); // set interrupt flag since an interruption has occurred	
				f.completeExceptionally(e);
			} catch (BadFormatException | IOException t) {
				log.warn("UNEXPECTED acquire fail: ",t);
				f.completeExceptionally(t);
			}
		});
		
		return f;
	}
}
