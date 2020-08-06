package convex.core.store;

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
	private final HashMap<Hash, Ref<?>> hashRefs = new HashMap<Hash, Ref<?>>();

	@Override
	@SuppressWarnings("unchecked")
	public <T> Ref<T> refForHash(Hash hash) {
		Ref<T> ref = (Ref<T>) hashRefs.get(hash);
		return ref;
	}
	
	@SuppressWarnings("unchecked")
	@Override
	public Ref<ACell> announceRef(Ref<ACell> ref, Consumer<Ref<ACell>> noveltyHandler) {

		// check store for existing ref first. Return this is we have it
		Hash hash = ref.getHash();
		Ref<ACell> existing = refForHash(hash);
		if ((existing != null)&&(existing.getStatus()>=Ref.ANNOUNCED)) return existing;

		// Convert to direct Ref. Don't want to store a soft ref!
		ref = ref.toDirect();

		ACell o = ref.getValue();
		if (o instanceof ACell) {
			// need to do recursive persistence
			o = ((ACell) o).updateRefs(r -> {
				return announceRef(r,noveltyHandler);
			});
		}
		ref=ref.withValue(o);
		final ACell oTemp=o;
		log.log(Stores.PERSIST_LOG_LEVEL,()->"Announcing ref "+hash.toHexString()+" of class "+Utils.getClassName(oTemp)+" with store "+this);

		ref=ref.withMinimumStatus(Ref.ANNOUNCED);
		hashRefs.put(hash, ref);

		if (noveltyHandler != null) noveltyHandler.accept(ref);

		return ref;
	}

	@SuppressWarnings("unchecked")
	@Override
	public Ref<ACell> persistRef(Ref<ACell> ref, Consumer<Ref<ACell>> noveltyHandler) {

		// check store for existing ref first. Return this is we have it
		Hash hash = ref.getHash();
		Ref<ACell> existing = refForHash(hash);
		if ((existing != null)&&(existing.getStatus()>=Ref.PERSISTED)) return existing;

		// Convert to direct Ref. Don't want to store a soft ref!
		ref = ref.toDirect();

		if (!(ref.getValue() instanceof ACell)) {
			System.out.println("PANIC!");
		}
		ACell o = ref.getValue();
		if (o instanceof ACell) {
			// need to do recursive persistence
			o = ((ACell) o).updateRefs(r -> {
				return r.persist(noveltyHandler);
			});
		}
		ref=ref.withValue(o);
		final ACell oTemp=o;
		log.log(Stores.PERSIST_LOG_LEVEL,()->"Persisting ref "+hash.toHexString()+" of class "+Utils.getClassName(oTemp)+" with store "+this);

		
		hashRefs.put(hash, ref);

		if (noveltyHandler != null) noveltyHandler.accept(ref);

		return ref.withMinimumStatus(Ref.PERSISTED);
	}

	@Override
	public Ref<ACell> storeRef(Ref<ACell> ref, Consumer<Ref<ACell>> noveltyHandler) {

		// check store for existing ref first. Return this is we have it
		Hash hash = ref.getHash();
		Ref<ACell> existing = refForHash(hash);
		if (existing != null) return existing; // already stored, so quick return

		// Convert to direct Ref. Don't want to store a soft ref!
		ref = ref.toDirect();
		ref=ref.withMinimumStatus(Ref.STORED);

		hashRefs.put(hash, ref);

		if (noveltyHandler != null) noveltyHandler.accept(ref);
		return ref;
	}
}
