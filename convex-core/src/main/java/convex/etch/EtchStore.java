package convex.etch;

import java.io.File;
import java.io.IOException;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.core.data.ACell;
import convex.core.data.Hash;
import convex.core.data.IRefFunction;
import convex.core.data.Ref;
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
	private static final Logger log = LoggerFactory.getLogger(EtchStore.class.getName());

	/**
	 * Etch file instance for the current store
	 */
	private Etch etch;

	/**
	 * Etch file instance for GC destination
	 */
	private Etch target;

	public EtchStore(Etch etch) {
		this.etch = etch;
		this.target = null;
		etch.setStore(this);
	}

	/**
	 * Starts a GC cycle. Creates a new Etch file for collection, and directs all
	 * new writes to the new store
	 * 
	 * @throws IOException If an IO exception occurs
	 */
	public synchronized void startGC() throws IOException {
		if (target != null)
			throw new Error("Already collecting!");
		File temp = new File(etch.getFile().getCanonicalPath() + "~");
		target = Etch.create(temp);

		// copy across current root hash
		target.setRootHash(etch.getRootHash());
	}

	private Etch getWriteEtch() {
		if (target != null)
			synchronized (this) {
				if (target != null)
					return target;
			}
		return etch;
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
		try {
			Ref<ACell> existing = (Ref<ACell>) refCache.getCell(hash);
			if (existing != null)
				return (Ref<T>) existing;

			if (hash == Hash.NULL_HASH)
				return (Ref<T>) Ref.NULL_VALUE;
			existing = readStoreRef(hash);
			return (Ref<T>) existing;
		} catch (IOException e) {
			throw Utils.sneakyThrow(e);
		}
	}

	public <T extends ACell> Ref<T> readStoreRef(Hash hash) throws IOException {
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

	@SuppressWarnings("unchecked")
	public <T extends ACell> Ref<T> storeRef(Ref<T> ref, int requiredStatus, Consumer<Ref<ACell>> noveltyHandler,
			boolean topLevel) throws IOException {

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
			Ref<T> existing = refForHash(hash);
			if (existing != null) {
				// Return existing ref if status is sufficient
				if (existing.getStatus() >= requiredStatus) {
					return existing;
				}
			}
		}

		if (requiredStatus < Ref.STORED) {
			if (topLevel || !embedded) {
				addToCache(ref);
			}
			return ref;
		}

		// beyond STORED level, need to recursively persist child refs if they exist
		if ((requiredStatus > Ref.STORED) && (cell.getRefCount() > 0)) {
			// TODO: probably slow to rebuild these all the time!
			IRefFunction func = r -> {
				try {
					return storeRef((Ref<ACell>) r, requiredStatus, noveltyHandler, false);
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
			if (log.isTraceEnabled()) {
				log.trace("Etch persisting at status=" + requiredStatus + " hash = 0x" + fHash.toHexString()
						+ " ref of class " + Utils.getClassName(cell) + " with store " + this);
			}

			// ensure status is set when we write to store
			ref = ref.withMinimumStatus(requiredStatus);
			ref = etch.write(fHash, ref);

			if (!embedded) {
				// Ensure we have soft Ref pointing to this store
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
		// Ensure data if persisted at sufficient level
		Ref<T> ref = storeTopRef(Ref.get(data), Ref.PERSISTED, null);
		Hash h = Hash.get(data);
		Etch etch = getWriteEtch();
		etch.setRootHash(h);
		etch.writeDataLength(); // ensure data length updated for root data addition
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

}
