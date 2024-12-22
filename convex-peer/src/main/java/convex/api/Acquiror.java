package convex.api;

import java.io.IOException;
import java.util.HashSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.core.Result;
import convex.core.cpos.CPoSConstants;
import convex.core.data.ACell;
import convex.core.data.AVector;
import convex.core.data.Cells;
import convex.core.data.Hash;
import convex.core.data.Ref;
import convex.core.data.prim.CVMLong;
import convex.core.exceptions.BadFormatException;
import convex.core.exceptions.MissingDataException;
import convex.core.exceptions.ResultException;
import convex.core.lang.RT;
import convex.core.message.Message;
import convex.core.store.AStore;
import convex.core.util.ThreadUtils;
import convex.core.util.Utils;

/**
 * Utility class for acquiring data remotely
 */
public class Acquiror {
	
	private static final Logger log = LoggerFactory.getLogger(Acquiror.class.getName());
	
	// Probably don't need this, can time out the future
	// private static final int ACQUIRE_LOOP_TIMEOUT=2000; 


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
	
		ThreadUtils.runWithStore(store,()-> {
			try {
				HashSet<Hash> missingSet = new HashSet<>();

				// Loop until future is complete or cancelled
				long LIMIT=CPoSConstants.MISSING_LIMIT; // limit of missing data elements to query at any time
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
						if (missingSet.isEmpty()) {
							f.complete(ref.getValue());
							return;
						}					
					}
					CVMLong id=CVMLong.create(source.getNextID());
					Message dataRequest=Message.createDataRequest(id, missingSet.toArray(Utils.EMPTY_HASHES));
					CompletableFuture<Result> cf=source.message(dataRequest);
					try {
						Result resp=cf.get();
						if (resp.isError()) {
							f.completeExceptionally(new ResultException(resp));
							log.info("Failed to request missing data: "+resp);
							return;
						}
						
						AVector<ACell> v=RT.ensureVector(resp.getValue());
						if (v==null) throw new BadFormatException("Expected Vector in data result for id "+id+" but was: "+resp);
						for (int i=0; i<v.count(); i++) {
							ACell val=v.get(i);
							if (val==null) {
								// null vector element implies missing at other end
								AVector<ACell> reqv=dataRequest.getPayload();
								Hash expectedHash=RT.ensureHash(reqv.get(i+2));
								f.completeExceptionally(new MissingDataException(store,expectedHash));
								continue;
							}
							Cells.store(val, store);
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
			} catch (NullPointerException |BadFormatException | IOException t) {
				log.warn("UNEXPECTED acquire fail: ",t);
				f.completeExceptionally(t);
			}
		});
		
		return f;
	}
}
