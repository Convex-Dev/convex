package convex.core.store;

import java.io.IOException;
import java.util.HashMap;
import java.util.Objects;
import java.util.function.Consumer;

import convex.core.cvm.CVMEncoder;
import convex.core.data.ACell;
import convex.core.data.AEncoder;
import convex.core.data.Blob;
import convex.core.data.Format;
import convex.core.data.Hash;
import convex.core.data.Ref;
import convex.core.data.RefDirect;
import convex.core.data.Refs;
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
	 * Encoder whose branch references resolve against this store.
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
		if (hash==Hash.NULL_HASH) return (Ref<T>) RefDirect.NULL_VALUE;
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
		Hash hash=(encoding.count()<=Format.MAX_EMBEDDED_LENGTH)
			?encoding.cachedContentHash():encoding.getContentHash();
		Ref<?> cached=(hash==null)?null:hashRefs.get(hash);
		if (cached!=null) return (T) CVMEncoder.specialise(cached.getValue());

		// Branches are bound to this store by the encoder.
		ACell decoded = encoder.decode(encoding);
		return (T)decoded;
	}
	

	@Override
	public AEncoder<ACell> getEncoder() {
		return encoder;
	}
	
	public <T extends ACell> Ref<T> persistRef(Ref<T> ref, Consumer<Ref<ACell>> noveltyHandler, int requiredStatus, boolean topLevel) {
		return persistRef(ref,noveltyHandler,requiredStatus,topLevel,0);
	}

	@SuppressWarnings("unchecked")
	private <T extends ACell> Ref<T> persistRef(Ref<T> ref, Consumer<Ref<ACell>> noveltyHandler, int requiredStatus, boolean topLevel, int depth) {
		// Convert to direct Ref if possible
		try {
			ref = ref.toDirect();
		} catch (MissingDataException e) {
			// Data not yet available (e.g. during remote acquisition), return unchanged
			return ref;
		}

		final T o=ref.getValue();
		if (o==null) return (Ref<T>) RefDirect.NULL_VALUE;

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
			Ref<ACell> result = persistChild(r,noveltyHandler,requiredStatus,depth+1);
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
		// A rebuilt cell starts with no Ref: updateRefs carries only facts about the value,
		// and leaves the Ref to the store that knows the status. Hand it over, as EtchStore
		// does, so the cell's hash and status are known without another walk.
		cell.attachRef(ref);
		if (topLevel||!embedded) {
			// Persist at top level
			final Hash fHash = (hash!=null)?hash:ref.getHash();

			hashRefs.put(fHash, (Ref<ACell>) ref);
			if (noveltyHandler != null) noveltyHandler.accept((Ref<ACell>) ref);
		}
		return ref;
	}

	/**
	 * Persists a child Ref at the given nesting depth, persisting what lies beneath it
	 * bottom up first if that depth is too deep to recurse further.
	 */
	@SuppressWarnings("unchecked")
	private Ref<ACell> persistChild(Ref<ACell> ref, Consumer<Ref<ACell>> noveltyHandler, int requiredStatus, int depth) {
		if (depth>=MAX_PERSIST_DEPTH) {
			ACell cell;
			try {
				cell=ref.getValue();
			} catch (MissingDataException e) {
				cell=null; // persistRef handles missing data
			}
			if (cell!=null) {
				persistBeneath(cell,r->isPersisted(r,requiredStatus),r->persistRef(r,noveltyHandler,requiredStatus,false,0));
			}
			depth=0;
		}
		return persistRef(ref,noveltyHandler,requiredStatus,false,depth);
	}

	/**
	 * Tests whether this store holds a branch Ref at the given status or above, as
	 * persistRef checks before recursing.
	 */
	private boolean isPersisted(Ref<ACell> ref, int requiredStatus) {
		// The Ref's own status may come from another store, so look in this one
		if (ref.isEmbedded()) return false;
		Ref<ACell> existing=refForHash(ref.getHash());
		return (existing!=null)&&(existing.getStatus()>=requiredStatus);
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

	/**
	 * Sets the root data, held in memory. Unlike {@link AStore#setRootData}, this
	 * does not persist the root's tree in this store.
	 */
	@Override
	public <T extends ACell> Ref<T> setRootData(T data) {
		rootData=data;
		return Ref.get(data);
	}

	@Override
	public void flush() {
		// Volatile store: there is no physical durability barrier.
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
