package convex.core.store;

import java.io.IOException;
import java.util.HashMap;
import java.util.Objects;
import java.util.function.Consumer;

import convex.core.cvm.CVMEncoder;
import convex.core.data.ACell;
import convex.core.data.AEncoder;
import convex.core.data.Blob;
import convex.core.data.Hash;
import convex.core.data.Ref;
import convex.core.exceptions.BadFormatException;
import convex.core.exceptions.MissingDataException;

/**
 * Class implementing direct in-memory caching and storage of hashed node data. 
 * 
 * Does not release storage unless closed or entire store is GC'd: mainly useful for testing
 * 
 * Persists refs as direct refs, i.e. retains fully in memory
 */
public class MemoryStore extends AStore {
	public static final MemoryStore DEFAULT = new MemoryStore();

	/**
	 * Store-bound encoder. Manages thread-local store context during decode.
	 */
	protected final CVMEncoder encoder=new CVMEncoder(this);

	/**
	 * Storage of persisted Refs for each hash value
	 */
	private final HashMap<Hash, Ref<ACell>> hashRefs = new HashMap<Hash, Ref<ACell>>();

	private ACell rootData;

	@Override
	@SuppressWarnings("unchecked")
	public <T extends ACell> Ref<T> refForHash(Hash hash) {
		Ref<T> ref = (Ref<T>) hashRefs.get(hash);
		if (ref!=null) return ref;
		if (hash==Hash.NULL_HASH) return (Ref<T>) Ref.NULL_VALUE;
		return null;
	}
	
	@Override
	public <T extends ACell> Ref<T> storeRef(Ref<T> r2, int status, Consumer<Ref<ACell>> noveltyHandler) {
		return persistRef(r2,noveltyHandler,status,false); 
	}

	@Override
	public <T extends ACell> Ref<T> storeTopRef(Ref<T> ref, int status,Consumer<Ref<ACell>> noveltyHandler) {
		return persistRef(ref,noveltyHandler,status,true); 
	}
	
	@Override
	@SuppressWarnings("unchecked")
	public final <T extends ACell> T decode(Blob encoding) throws BadFormatException {
		Hash hash=encoding.getContentHash();
		Ref<?> cached= hashRefs.get(hash);
		if (cached!=null) return (T) cached.getValue();

		// Encoder manages thread-local store context
		ACell decoded = encoder.decode(encoding);
		return (T)decoded;
	}
	

	@Override
	public AEncoder<ACell> getEncoder() {
		return encoder;
	}
	
	@SuppressWarnings("unchecked")
	public <T extends ACell> Ref<T> persistRef(Ref<T> ref, Consumer<Ref<ACell>> noveltyHandler, int requiredStatus, boolean topLevel) {
		// Convert to direct Ref if possible
		try {
			ref = ref.toDirect();
		} catch (MissingDataException e) {
			// Data not yet available (e.g. during remote acquisition), return unchanged
			return ref;
		}

		final T o=ref.getValue();
		if (o==null) return (Ref<T>) Ref.NULL_VALUE;

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

		// Recursively persist children, tracking if all were fully resolved
		final int[] minChildStatus = {requiredStatus};
		cell  = cell.updateRefs(r -> {
			Ref<ACell> result = persistRef(r,noveltyHandler,requiredStatus,false);
			if (result.getStatus()<requiredStatus) {
				minChildStatus[0]=Math.min(minChildStatus[0], result.getStatus());
			}
			return result;
		});

		ref=ref.withValue((T)cell);

		// Only claim full status if all children achieved it;
		// otherwise cap at STORED (this cell is stored but descendants may be incomplete)
		int achievedStatus = (minChildStatus[0]>=requiredStatus) ? requiredStatus : Math.max(Ref.STORED, minChildStatus[0]);
		ref=ref.withMinimumStatus(achievedStatus);
		if (topLevel||!embedded) {
			// Persist at top level
			final Hash fHash = (hash!=null)?hash:ref.getHash();

			hashRefs.put(fHash, (Ref<ACell>) ref);
			if (noveltyHandler != null) noveltyHandler.accept((Ref<ACell>) ref);
		}
		return ref;
	}

	@Override
	public Hash getRootHash() throws IOException {
		if (rootData==null) return null;
		return rootData.getHash();
	}
	
	@SuppressWarnings("unchecked")
	@Override
	public <T extends ACell> T getRootData() throws IOException {
		return (T) rootData;
	}

	@Override
	public <T extends ACell> Ref<T> setRootData(T data) {
		rootData=data;
		return Ref.get(data);
	}

	@Override
	public void close() {
		hashRefs.clear();
		rootData=null;
	}

	@Override
	public <T extends ACell> Ref<T> checkCache(Hash h) {
		return refForHash(h);
	}

	@Override
	public String shortName() {
		return "Memory Store "+Objects.toIdentityString(this);
	}

}
