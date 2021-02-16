package convex.core.store;

import java.io.IOException;
import java.util.HashMap;
import java.util.function.Consumer;
import java.util.logging.Logger;

import convex.core.crypto.Hash;
import convex.core.data.ACell;
import convex.core.data.Ref;
import convex.core.util.Utils;

/**
 * Class implementing caching and storage of hashed node data
 * 
 * Persists refs as direct refs, i.e. retains fully in memory
 */
public class MemoryStore extends AStore {
	public static final MemoryStore DEFAULT = new MemoryStore();
	
	private static final Logger log = Logger.getLogger(MemoryStore.class.getName());

	/**
	 * Storage of persisted Refs for each hash value
	 */
	private final HashMap<Hash, Ref<ACell>> hashRefs = new HashMap<Hash, Ref<ACell>>();

	private Hash rootHash;

	@Override
	@SuppressWarnings("unchecked")
	public <T extends ACell> Ref<T> refForHash(Hash hash) {
		Ref<T> ref = (Ref<T>) hashRefs.get(hash);
		return ref;
	}
	
	@Override
	public <T extends ACell> Ref<T> storeRef(Ref<T> r2, int status, Consumer<Ref<ACell>> noveltyHandler) {
		return persistRef(r2,noveltyHandler,status,false); 
	}

	@Override
	public <T extends ACell> Ref<T> storeTopRef(Ref<T> ref, int status,Consumer<Ref<ACell>> noveltyHandler) {
		return persistRef(ref,noveltyHandler,status,true); 
	}
	
	@SuppressWarnings("unchecked")
	public <T extends ACell> Ref<T> persistRef(Ref<T> ref, Consumer<Ref<ACell>> noveltyHandler, int requiredStatus, boolean topLevel) {
		// Convert to direct Ref. Don't want to store a soft ref!
		ref = ref.toDirect();

		final T o=ref.getValue();
		if (!(o instanceof ACell)) {
			return ref.withMinimumStatus(Ref.MAX_STATUS);
		}
		
		ACell cell = (ACell) o;
		boolean embedded=cell.isEmbedded();
		
		Hash hash=null;
		if (!embedded) {
			// check store for existing ref first. Return this is we have it
			hash = ref.getHash();
			Ref<T> existing = refForHash(hash);
			if ((existing != null)) {
				if (existing.getStatus()>=requiredStatus) return existing;
				ref=existing;
			}
		}
		
		// need to do recursive persistence
		cell  = cell.updateRefs(r -> {
			return r.persist(noveltyHandler);
		});
		
		ref=ref.withValue((T)cell);
		final ACell oTemp=cell;

		if (topLevel||!embedded) {
			final Hash fHash = (hash!=null)?hash:ref.getHash();
			log.log(Stores.PERSIST_LOG_LEVEL,()->"Persisting ref 0x"+fHash.toHexString()+" of class "+Utils.getClassName(oTemp)+" with store "+this);

			hashRefs.put(fHash, (Ref<ACell>) ref);
			if (noveltyHandler != null) noveltyHandler.accept((Ref<ACell>) ref);
		}
		return ref.withMinimumStatus(requiredStatus);
	}

	@Override
	public Hash getRootHash() throws IOException {
		return rootHash;
	}

	@Override
	public void setRootHash(Hash h) {
		rootHash=h;
	}

	@Override
	public void close() {
		hashRefs.clear();
		rootHash=null;
	}
}
