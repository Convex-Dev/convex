package convex.etch;

import java.io.File;
import java.io.IOException;
import java.nio.channels.ClosedChannelException;
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

	/**
	 * True while a GC cycle is being cancelled: writes are redirected back to
	 * the old file while the target is drained and reverse-migrated. target
	 * stays non-null (reads still fall back to it) until cancellation completes.
	 */
	private volatile boolean cancelling = false;

	/**
	 * Count of in-flight top-level persists writing to the GC target. cancelGC()
	 * drains these to zero before the reverse migration: the index scan requires
	 * a write-quiescent file. Only touched while a cycle is in progress.
	 */
	private final java.util.concurrent.atomic.AtomicInteger targetWriters = new java.util.concurrent.atomic.AtomicInteger();

	/**
	 * Monitor for drain signalling, separate from the store monitor so a persist
	 * finishing during the (long) reverse migration never blocks on it.
	 */
	private final Object drainSignal = new Object();


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
	 * @throws IllegalStateException if a GC cycle is already in progress
	 */
	public synchronized void startGC() throws IOException {
		if (target != null)
			throw new IllegalStateException("GC already in progress for store: " + this);

		// Generational target naming: an existing target file is either stale
		// (interrupted cycle: may hold data, so it must be neither adopted nor
		// deleted — recovery is a separate concern) or a cancelled target pinned
		// by mapped buffers (Windows). Skip to the first free name
		String base = etch.getFile().getCanonicalPath() + "~";
		File temp = new File(base);
		for (int i = 1; temp.exists(); i++) {
			if (i >= 100) throw new IllegalStateException("Too many stale GC target files: " + base);
			temp = new File(base + i);
		}

		// Fully initialise the target before publication: readers must never see
		// a target without its store binding and root hash
		Etch t = Etch.create(temp);
		t.setStore(this);
		t.setRootHash(etch.getRootHash());
		target = t;
	}

	/**
	 * Cancels the GC cycle in progress. Writes are redirected back to the
	 * original file, in-flight target writes are drained, and everything
	 * written to the target (including root updates) is migrated back before
	 * the target file is dropped. Nothing persisted during the cycle is lost.
	 *
	 * May be called again if a previous attempt failed part-way (e.g. an IO
	 * error during migration): the reverse migration is idempotent.
	 *
	 * @throws IOException in case of IO error during the reverse migration
	 * @throws IllegalStateException if no GC cycle is in progress
	 */
	public synchronized void cancelGC() throws IOException {
		Etch t = target;
		if (t == null)
			throw new IllegalStateException("No GC in progress for store: " + this);

		// 1: redirect writes back to the old file. Reads keep the target
		// fallback until migration completes, so nothing becomes unreadable
		cancelling = true;

		// 2: drain in-flight persists still writing to the target — the reverse
		// migration's index scan requires a write-quiescent file. Any persist
		// that registered before seeing the cancelling flag re-checks after
		// registering, so a zero count here is conclusive (Dekker-style)
		synchronized (drainSignal) {
			try {
				while (targetWriters.get() > 0) {
					drainSignal.wait(50); // timed: robust against missed notifies
				}
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new IOException("Interrupted while draining GC target writers", e);
			}
		}

		// 3: migrate everything in the target back into the old file. The
		// destination is this store, whose writes now route to the old file
		EtchUtils.migrate(t, this);

		// 4: carry back the root hash (its tree was just migrated). setRootData
		// is synchronised on the store, so this cannot stomp a newer root
		etch.setRootHash(t.getRootHash());
		etch.writeDataLength();
		etch.flush();

		// 5: retire the target. Readers may still hold the target reference
		// briefly: readStoreRef tolerates a closed target (everything it held is
		// now in the old file). Deletion may fail while mapped buffers pin the
		// file (Windows): the next startGC picks a fresh generational name
		target = null;
		cancelling = false;
		t.close();
		File tf = t.getFile();
		if (!tf.delete()) {
			tf.deleteOnExit();
		}
	}

	/**
	 * Checks if a GC cycle is currently in progress
	 * @return true if collecting
	 */
	public boolean isGCInProgress() {
		return target != null;
	}

	private Etch getWriteEtch() {
		// Note || short-circuit: the common idle case reads one volatile field
		Etch t = target;
		return (t == null || cancelling) ? etch : t;
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
			try {
				Ref<T> ref = t.read(hash);
				if (ref != null) {
					refCache.putCell(ref);
					return ref;
				}
			} catch (ClosedChannelException e) {
				// The target was concurrently retired by a completing cancelGC():
				// everything it held has been migrated to the old file, so falling
				// through is correct (NOT a masked read failure)
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
		Etch we = getWriteEtch();
		if (we == etch) {
			// fast path: not writing to a GC target
			return storeRef(ref, requiredStatus, noveltyHandler, topLevel, we);
		}

		// Writing to the GC target: register so cancelGC() can drain in-flight
		// persists to write-quiescence before its reverse migration
		targetWriters.incrementAndGet();
		try {
			// Re-check AFTER registering (Dekker-style pairing with cancelGC's
			// flag-then-drain): if cancellation started meanwhile, this persist
			// must go to the old file
			we = getWriteEtch();
			return storeRef(ref, requiredStatus, noveltyHandler, topLevel, we);
		} finally {
			if ((targetWriters.decrementAndGet() == 0) && cancelling) {
				synchronized (drainSignal) {
					drainSignal.notifyAll();
				}
			}
		}
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
			// During a GC cycle, only an entry in the file being written can
			// satisfy a persist: the shared cache cannot prove file residency, and
			// early-returning on a hit in the OTHER file would skip a copy —
			// breaking INV-1 while collecting, or losing data during a cancel's
			// reverse migration. Outside a cycle the cached path is unchanged
			Ref<T> existing = (target == null) ? refForHash(hash) : writeEtch.read(hash);
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
		// The root lives in the target for the WHOLE cycle, including during a
		// cancel (writes are already redirected, but the root is only copied back
		// at the end of the reverse migration) — so this is target-aware rather
		// than using getWriteEtch()
		Etch t = target;
		if (t != null) {
			try {
				return t.getRootHash();
			} catch (ClosedChannelException e) {
				// target concurrently retired by cancelGC(): root was copied back
			}
		}
		return etch.getRootHash();
	}

	@Override
	public synchronized <T extends ACell> Ref<T> setRootData(T data) throws IOException {
		// Synchronised on the store: excluded during cancelGC(), whose root
		// copy-back could otherwise stomp a newer root set mid-cancellation.
		// Root updates are infrequent, so the uncontended monitor is cheap.
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
