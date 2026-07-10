package convex.etch;

import java.io.File;
import java.io.IOException;
import java.util.function.Consumer;

import convex.core.data.ACell;
import convex.core.data.Hash;
import convex.core.data.IRefFunction;
import convex.core.data.Ref;
import convex.core.exceptions.StoreException;
import convex.core.store.ACachedStore;
import convex.core.util.FileUtils;
import convex.core.util.Utils;

/**
 * Class implementing on-disk memory-mapped storage of Convex data.
 *
 *
 * "There are only two hard things in Computer Science: cache invalidation and
 * naming things." - Phil Karlton
 *
 * Objects are keyed by cryptographic hash. That solves naming. Objects are
 * immutable. That solves cache invalidation.
 *
 * Garbage collection is left as an exercise for the reader.
 */
public class EtchStore extends ACachedStore {

	/**
	 * Etch file instance for the current store
	 */
	private volatile Etch etch;

	/**
	 * Etch file instance for GC destination, or null if no GC cycle is in
	 * progress. Fully initialised before publication; volatile so lock-free
	 * readers see a consistent instance.
	 */
	private volatile Etch target;


	public EtchStore(Etch etch) {
		this(etch, true);
	}

	public EtchStore(Etch etch, boolean enableL2) {
		super(enableL2);
		this.etch = etch;
		this.target = null;
		etch.setStore(this);
	}

	/**
	 * Starts a GC cycle. Creates a new target Etch file: subsequent writes are
	 * directed to the target, while reads check the target first and fall back
	 * to the old file. See convex-core/docs/ETCH_GC.md.
	 *
	 * @throws IOException If an IO exception occurs
	 * @throws IllegalStateException if a GC cycle is already in progress, or a
	 *         stale target file exists (crash recovery is the caller's concern)
	 */
	public synchronized void startGC() throws IOException {
		if (target != null)
			throw new IllegalStateException("GC already in progress for store: " + this);
		File temp = new File(etch.getFile().getCanonicalPath() + "~");
		if (temp.exists()) {
			// A stale target may hold data from an interrupted cycle: adopting or
			// deleting it blindly would risk data loss. Recovery is a separate step
			throw new IllegalStateException("Stale GC target file exists: " + temp);
		}

		// Fully initialise the target before publication: readers must never see
		// a target without its store binding and root hash
		Etch t = Etch.create(temp);
		t.setStore(this);
		t.setRootHash(etch.getRootHash());
		target = t;
	}

	/**
	 * Checks if a GC cycle is currently in progress
	 * @return true if collecting
	 */
	public boolean isGCInProgress() {
		return target != null;
	}

	private Etch getWriteEtch() {
		Etch t = target;
		return (t == null) ? etch : t;
	}

	/**
	 * Gets the GC target Etch instance, or null if no cycle is in progress.
	 * Internal / testing use.
	 */
	Etch getTargetEtch() {
		return target;
	}

	/**
	 * Creates an EtchStore using a specified file.
	 *
	 * @param file File to use for storage. Will be created it it does not already
	 *             exist.
	 * @return EtchStore instance
	 * @throws IOException If an IO error occurs
	 */
	public static EtchStore create(File file) throws IOException {
		file = FileUtils.ensureFilePath(file);
		Etch etch = Etch.create(file);
		return new EtchStore(etch);
	}

	/**
	 * Create an Etch store using a new temporary file with the given prefix
	 *
	 * @param prefix String prefix for temporary file
	 * @return New EtchStore instance
	 * @throws IOException In case of IO error creating database
	 */
	public static EtchStore createTemp(String prefix) throws IOException {
		Etch etch = Etch.createTempEtch(prefix);
		return new EtchStore(etch);
	}

	/**
	 * Create an Etch store using a new temporary file with a generated prefix
	 *
	 * @return New EtchStore instance
	 * @throws IOException In case of IO error creating database
	 */
	public static EtchStore createTemp() throws IOException {
		Etch etch = Etch.createTempEtch();
		return new EtchStore(etch);
	}

	@SuppressWarnings("unchecked")
	@Override
	public <T extends ACell> Ref<T> refForHash(Hash hash) {
		Ref<ACell> existing = checkCache(hash);
		if (existing != null)
			return (Ref<T>) existing;

		if (hash == Hash.NULL_HASH)
			return (Ref<T>) Ref.NULL_VALUE;
		try {
			return readStoreRef(hash);
		} catch (IOException e) {
			// Includes ClosedChannelException. A failed read is a fundamental store
			// failure: it must never be reported as the value being absent
			throw new StoreException("Store read failed: " + shortName(), e);
		}
	}

	public <T extends ACell> Ref<T> readStoreRef(Hash hash) throws IOException {
		// During a GC cycle, new writes land in the target: check it first, then
		// fall back to the old file for data not yet migrated. Outside a cycle
		// this costs a single volatile load and an untaken branch
		Etch t = target;
		if (t != null) {
			Ref<T> ref = t.read(hash);
			if (ref != null) {
				refCache.putCell(ref);
				return ref;
			}
		}
		Ref<T> ref = etch.read(hash);
		if (ref != null)
			refCache.putCell(ref);
		return ref;
	}

	@Override
	public <T extends ACell> Ref<T> storeRef(Ref<T> ref, int status, Consumer<Ref<ACell>> noveltyHandler) throws IOException {
		return storeRef(ref, status, noveltyHandler, false);
	}

	@Override
	public <T extends ACell> Ref<T> storeTopRef(Ref<T> ref, int status, Consumer<Ref<ACell>> noveltyHandler) throws IOException {
		return storeRef(ref, status, noveltyHandler, true);
	}

	public <T extends ACell> Ref<T> storeRef(Ref<T> ref, int requiredStatus, Consumer<Ref<ACell>> noveltyHandler,
			boolean topLevel) throws IOException {
		// Snapshot the write target ONCE per top-level call and thread it through
		// the recursion: a persist spanning startGC() then completes consistently
		// against the old file (linearising as before the cycle). Splitting one
		// persist across files would let a parent land in the GC target claiming
		// PERSISTED while its children exist only in the old file, silently
		// breaking the INV-1 pruning guarantee (see ETCH_GC.md)
		return storeRef(ref, requiredStatus, noveltyHandler, topLevel, getWriteEtch());
	}

	@SuppressWarnings("unchecked")
	private <T extends ACell> Ref<T> storeRef(Ref<T> ref, int requiredStatus, Consumer<Ref<ACell>> noveltyHandler,
			boolean topLevel, Etch writeEtch) throws IOException {

		// Get the value. If we are persisting, should be there!
		ACell cell = ref.getValue();

		// Quick handling for null
		if (cell == null)
			return (Ref<T>) Ref.NULL_VALUE;

		// check store for existing ref first.
		boolean embedded = cell.isEmbedded();
		Hash hash = null;
		// if not embedded, worth checking store first for existing value
		if (!embedded) {
			hash = ref.getHash();
			// During a GC cycle, only an entry in the TARGET can satisfy a persist:
			// the shared cache and the old file cannot prove target residency, and
			// early-returning on an old-file hit would skip the copy that INV-1
			// depends on. Outside a cycle (writeEtch == etch) the cached path is
			// unchanged
			Ref<T> existing = (writeEtch == etch) ? refForHash(hash) : writeEtch.read(hash);
			if (existing != null) {
				// Return existing ref if status is sufficient
				if (existing.getStatus() >= requiredStatus) {
					return existing;
				}
			}
		}

		if (requiredStatus < Ref.STORED) {
			// no write: only cache, and only refs belonging to this store
			if ((topLevel || !embedded) && !isForeign(ref)) {
				addToCache(ref);
			}
			return ref;
		}

		// beyond STORED level, need to recursively persist child refs if they exist
		if ((requiredStatus > Ref.STORED) && (cell.getRefCount() > 0)) {
			// TODO: probably slow to rebuild these all the time!
			IRefFunction func = r -> {
				try {
					return storeRef((Ref<ACell>) r, requiredStatus, noveltyHandler, false, writeEtch);
				} catch (IOException e) {
					// OK because overall function throws IOException
					throw Utils.sneakyThrow(e);
				}
			};

			// need to do recursive persistence
			// TODO: maybe switch to a stack? Mitigate risk of stack overflow?
			ACell newObject = cell.updateRefs(func);

			// perhaps need to update Ref
			if (cell != newObject) {
				ref = ref.withValue((T) newObject);
				cell = newObject;
			}
		}

		// Actually write top level an non-embedded cells only
		if (topLevel || !embedded) {

			// Do actual write to store
			final Hash fHash = (hash != null) ? hash : ref.getHash();

			// record exactly the status this write has proven for this store:
			// carried status (possibly from another store) is not evidence here
			ref = ref.withStatus(requiredStatus);
			ref = writeEtch.write(fHash, ref);

			// Ensure we have a soft Ref pointing to this store. Embedded top-level
			// cells normally keep their direct Ref, but a foreign-bound Ref must be
			// rebound: sound because the entry was just written here
			if (!embedded || isForeign(ref)) {
				ref = ref.toSoft(this);
			}

			cell.attachRef(ref); // make sure we are using current ref within cell
			addToCache(ref); // cache for subsequent writes

			// call novelty handler if newly persisted non-embedded
			if (noveltyHandler != null) {
				if (!embedded)
					noveltyHandler.accept((Ref<ACell>) ref);
			}
		} else {
			// no need to write, just tag updated status
			ref = ref.withMinimumStatus(requiredStatus);
		}
		cell.attachRef(ref);
		return ref;
	}

	protected <T extends ACell> void addToCache(Ref<T> ref) {
		// Guarantee: refs served from the cache are always for this store.
		// Defensive check: callers must enforce this before caching
		if (isForeign(ref)) {
			throw new IllegalArgumentException("Attempt to cache foreign Ref in store: " + this);
		}
		refCache.putCell(ref);
	}

	@Override
	public String toString() {
		try {
			return "EtchStore: " + getFile().getCanonicalPath();
		} catch (IOException e) {
			return "EtchStore: <File name lookup failed>";
		}
	}

	/**
	 * Gets the database file name for this EtchStore
	 *
	 * @return File name as a String
	 */
	public String getFileName() {
		return etch.getFileName();
	}

	public void close() {
		// Close the GC target too if a cycle is in progress. This abandons the
		// cycle: data written since startGC() remains in the target file for a
		// later recovery step, and the old file is untouched
		Etch t = target;
		if (t != null)
			t.close();
		etch.close();
	}

	/**
	 * Ensure the store is fully persisted to disk
	 * 
	 * @throws IOException If an IO error occurs
	 */
	public void flush() throws IOException {
		etch.flush();
		Etch target = this.target;
		if (target != null)
			target.flush();
	}

	public File getFile() {
		return etch.getFile();
	}

	@Override
	public Hash getRootHash() throws IOException {
		return getWriteEtch().getRootHash();
	}

	@Override
	public <T extends ACell> Ref<T> setRootData(T data) throws IOException {
		// Snapshot the write target once so the root tree and the root hash land
		// in the same file even if startGC() runs concurrently
		Etch writeEtch = getWriteEtch();
		// Ensure data is persisted at sufficient level
		Ref<T> ref = storeRef(Ref.get(data), Ref.PERSISTED, null, true, writeEtch);
		Hash h = Hash.get(data);
		writeEtch.setRootHash(h);
		writeEtch.writeDataLength(); // ensure data length updated for root data addition
		return ref;
	}

	/**
	 * Gets the underlying Etch instance
	 * 
	 * @return Etch instance
	 */
	public Etch getEtch() {
		return etch;
	}

	@Override
	public String shortName() {
		return "Etch: "+etch.getFileName();
	}

	@Override
	public boolean isPersistent() {
		return true;
	}
}
