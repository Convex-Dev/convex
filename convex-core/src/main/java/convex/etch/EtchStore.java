package convex.etch;

import java.io.File;
import java.io.IOException;
import java.nio.channels.ClosedChannelException;
import java.nio.file.Files;
import java.util.function.Consumer;
import java.util.function.Function;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AccountKey;
import convex.core.data.Hash;
import convex.core.data.IRefFunction;
import convex.core.data.Ref;
import convex.core.data.RefDirect;
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
 * Garbage collection copies retained data into a fresh Etch file.
 */
public class EtchStore extends ACachedStore {

	/** Physical file owned by this generation; retained until dependent views close. */
	private final Etch etch;
	private final EtchGC gc;
	private final EtchConfig config;
	volatile boolean closed;

	public EtchStore(Etch etch) {
		this(etch, etch.getConfig());
	}

	/**
	 * Compatibility constructor overriding the configured L2 policy. New callers
	 * can specify all settings through {@link EtchConfig} and {@link #create(File, EtchConfig)}.
	 */
	public EtchStore(Etch etch, boolean enableL2) {
		this(etch, etch.getConfig().withL2Enabled(enableL2));
	}

	private EtchStore(Etch etch, EtchConfig config) {
		this(etch,config,null);
	}

	EtchStore(Etch etch, EtchConfig config, EtchGC sharedGC) {
		super(config.getRefCacheSize(),config.isL2Enabled());
		this.config=config;
		this.etch=etch;
		this.gc=(sharedGC==null)?new EtchGC(this):sharedGC;
		etch.setStore(this);
	}

	void checkOpen() throws ClosedChannelException {
		if (closed) throw new ClosedChannelException();
	}

	/** Returns the resolved configuration used by this store, including its caches. */
	public EtchConfig getConfig() {
		return config;
	}

	/**
	 * Starts collection on the current generation. Persists through every open
	 * predecessor are drained before subsequent writes are directed to the target.
	 * @throws IOException if target creation fails
	 * @throws IllegalStateException if not current, already collecting, or called from a persistence callback
	 */
	public void startGC() throws IOException { gc.start(this); }

	/**
	 * Copies the current root tree into the target, preserving each entry's status.
	 * Safe alongside persists and root updates through any open generation.
	 * @throws IOException if transfer fails
	 */
	public void transferGC() throws IOException { gc.transfer(this); }

	/** @return whether this generation's active transfer has completed */
	public boolean isGCComplete() { return gc.isComplete(this); }

	/**
	 * Independently verifies the current root using only the collection target.
	 * @return hashes missing from the target
	 * @throws IOException if verification fails
	 */
	public java.util.List<Hash> verifyGC() throws IOException { return gc.verify(this); }

	/**
	 * Redirects all handles back to this generation and migrates target writes back.
	 * A failed cancellation can be retried; no persisted values are discarded.
	 * @throws IOException if cancellation fails
	 */
	public void cancelGC() throws IOException { gc.cancel(this); }

	/**
	 * Verifies and completes collection, returning a new store with fresh caches.
	 * Older views remain usable across subsequent collections until closed.
	 * @return the collected successor
	 * @throws IOException if verification or cutover fails
	 */
	public EtchStore completeGC() throws IOException { return completeGC(null); }

	/**
	 * Completes collection, optionally retaining a hard-link snapshot of the source.
	 * The backup must be a new filename on the same filesystem. Original entries
	 * and the pre-cycle root survive in the snapshot; subsequent writes use the
	 * collected store. Close this and older handles before opening the snapshot.
	 * @param backupFile snapshot path, or null
	 * @return the collected successor
	 * @throws IOException if verification, backup creation or cutover fails
	 */
	public EtchStore completeGC(File backupFile) throws IOException { return gc.complete(this,backupFile); }

	/**
	 * Exports an explicit root into a new, independent, closed Etch checkpoint.
	 * Reads/writes and GC may continue; even closing this handle does not revoke
	 * an export's acquired read lease. Interruption cancels before publication.
	 * The child-first copy establishes completeness; a separate full scan is
	 * available through {@link EtchVerifier#verifyPersisted(Etch)} at the caller's choice.
	 * The destination filesystem must support hard links for no-replace publication.
	 * See {@link EtchCheckpoint} for status timing and durability guarantees.
	 */
	public File exportCheckpoint(Hash root, File destination) throws IOException {
		return exportCheckpoint(root,destination,null);
	}

	/** Exports with explicitly supplied fields overriding this store's effective configuration. */
	public File exportCheckpoint(Hash root, File destination,
			AMap<AString,ACell> overrides) throws IOException {
		return exportCheckpoint(root,destination,overrides,null);
	}

	/** Exports with partial configuration and an optional replacement key resolver. */
	public File exportCheckpoint(Hash root, File destination,
			AMap<AString,ACell> overrides,
			Function<AccountKey,byte[]> keyResolver) throws IOException {
		return EtchCheckpoint.export(this,root,destination,config.withOverrides(overrides,keyResolver));
	}

	EtchReadView lease() throws IOException { return gc.lease(this); }

	/**
	 * Checks a proposed backup path without creating or modifying any file.
	 * @param backupFile proposed backup file
	 * @return canonical backup file
	 * @throws IOException if the path is occupied or conflicts with GC files
	 */
	public File validateGCBackup(File backupFile) throws IOException {
		File backup = backupFile.getCanonicalFile();
		File base = getBaseFile().getCanonicalFile();
		String name = backup.getName();
		if (base.getParentFile().equals(backup.getParentFile())
				&& (name.equals(base.getName()) || name.startsWith(base.getName()+"~")
						|| name.startsWith(base.getName()+".gc-"))) {
			throw new IOException("Backup path conflicts with Etch GC files: " + backup);
		}
		if (Files.exists(backupFile.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
			throw new IOException("Backup file already exists: " + backupFile);
		}
		return backup;
	}

	/**
	 * Checks if a GC cycle is currently in progress
	 * @return true if collecting (not after cutover)
	 */
	public boolean isGCInProgress() { return gc.isInProgress(this); }

	/** Current target, for package-internal lifecycle tests. */
	Etch getTargetEtch() { return gc.getTarget(this); }

	/**
	 * Creates an EtchStore using a specified file.
	 *
	 * @param file File to use for storage. Will be created it it does not already
	 *             exist.
	 * @return EtchStore instance
	 * @throws IOException If an IO error occurs
	 */
	public static EtchStore create(File file) throws IOException {
		return createConfigured(file,null);
	}

	/**
	 * Creates an EtchStore using a specified file and compiled configuration.
	 * The configuration is used for GC recovery and the eventual store open, so
	 * encrypted v3 files retain their caller-supplied key function and policy while
	 * reconciling lifecycle files.
	 *
	 * @param file File to use for storage. Will be created if it does not already
	 *             exist.
	 * @param config compiled Etch configuration
	 * @return EtchStore instance
	 * @throws IOException If an IO error occurs
	 */
	public static EtchStore create(File file, EtchConfig config) throws IOException {
		if (config==null) throw new IllegalArgumentException("Etch config cannot be null");
		return createConfigured(file,config);
	}

	private static EtchStore createConfigured(File file, EtchConfig config) throws IOException {
		file = FileUtils.ensureFilePath(file);
		// Automatic GC adoption/recovery: reconcile any completed or abandoned
		// GC cycle state before opening (see ETCH_GC.md "File lifecycle").
		// Normally returns the same file; returns the cutover-chain tail when
		// adoption had to be deferred (e.g. mapped-file pinning on Windows)
		File open = (config==null)?EtchUtils.recover(file):EtchUtils.recover(file,config);
		Etch etch = (config==null)?Etch.create(open):Etch.create(open,config);
		EtchStore store = new EtchStore(etch);
		// The logical base stays the REQUESTED file even under deferred
		// adoption, so future GC targets are named off it (bounded names)
		store.gc.setBaseFile(file);
		return store;
	}

	/**
	 * Gets the logical base file for this store: the file name the store is
	 * known by, used for naming GC target files. Usually the same as
	 * getFile(), but differs when running on a cutover-chain tail under
	 * deferred adoption.
	 *
	 * @return Logical base file
	 */
	public File getBaseFile() {
		return gc.getBaseFile();
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
	 * Creates a configured Etch store using a new temporary file with the given
	 * prefix.
	 *
	 * @param prefix String prefix for temporary file
	 * @param config compiled Etch configuration
	 * @return New EtchStore instance
	 * @throws IOException In case of IO error creating database
	 */
	public static EtchStore createTemp(String prefix, EtchConfig config) throws IOException {
		if (config==null) throw new IllegalArgumentException("Etch config cannot be null");
		return new EtchStore(Etch.createTempEtch(prefix,config));
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

	/**
	 * Creates a configured Etch store using a new temporary file with a generated
	 * prefix.
	 *
	 * @param config compiled Etch configuration
	 * @return New EtchStore instance
	 * @throws IOException In case of IO error creating database
	 */
	public static EtchStore createTemp(EtchConfig config) throws IOException {
		if (config==null) throw new IllegalArgumentException("Etch config cannot be null");
		return new EtchStore(Etch.createTempEtch(config));
	}

	@SuppressWarnings("unchecked")
	@Override
	public <T extends ACell> Ref<T> refForHash(Hash hash) {
		Ref<ACell> existing = checkCache(hash);
		if (existing != null)
			return (Ref<T>) existing;

		if (hash == Hash.NULL_HASH)
			return (Ref<T>) RefDirect.NULL_VALUE;
		try {
			return readStoreRef(hash);
		} catch (IOException e) {
			// Includes ClosedChannelException. A failed read is a fundamental store
			// failure: it must never be reported as the value being absent
			throw new StoreException("Store read failed: " + shortName(), e);
		}
	}

	public <T extends ACell> Ref<T> readStoreRef(Hash hash) throws IOException {
		Ref<T> ref=gc.read(this,hash);
		if (ref!=null) refCache.putCell(ref);
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
		return gc.persist(this,ref,requiredStatus,noveltyHandler,topLevel);
	}

	@SuppressWarnings("unchecked")
	<T extends ACell> Ref<T> storeRef(Ref<T> ref, int requiredStatus, Consumer<Ref<ACell>> noveltyHandler,
			boolean topLevel, EtchGC.State state) throws IOException {
		Etch writeEtch=state.writeEtch();

		// Get the value. If we are persisting, should be there!
		ACell cell = ref.getValue();

		// Quick handling for null
		if (cell == null)
			return (Ref<T>) RefDirect.NULL_VALUE;

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
			Ref<T> existing = (state.target==null && state.owner==this)
					? refForHash(hash) : writeEtch.read(hash,this);
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
				// Direct refs cannot prove residency, even if persisted elsewhere.
				addToCache(ref.isDirect() ? ref.withStatus(Ref.UNKNOWN) : ref);
			}
			return ref;
		}

		int writeStatus = requiredStatus;
		if ((writeEtch != etch) && (topLevel || !embedded)) {
			// GC moves entries within this logical store. The old file's recorded
			// status is evidence; status carried by an incoming ref is not. Do this
			// on every copy, so a later parent prune cannot hide a demoted child.
			if (hash == null) hash = ref.getHash();
			writeStatus=Math.max(writeStatus,gc.retainedStatus(this,state,writeEtch,hash));
		}
		// Retaining a PERSISTED/ANNOUNCED entry also requires its tree in the
		// target, even for a STORED-only request. Its old ANNOUNCED status must
		// not promote children: preserve their own old statuses independently.
		final int childStatus = (writeStatus >= Ref.PERSISTED)
				? Math.max(requiredStatus, Ref.PERSISTED) : requiredStatus;

		// beyond STORED level, need to recursively persist child refs if they exist
		if ((childStatus > Ref.STORED) && (cell.getRefCount() > 0)) {
			// TODO: probably slow to rebuild these all the time!
			IRefFunction func = r -> {
				try {
					return storeRef((Ref<ACell>) r, childStatus, noveltyHandler, false, state);
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

			// Record the requested status plus any status retained from our own
			// old GC file, after proving the required target-tree presence.
			ref = ref.withStatus(writeStatus);
			ref = writeEtch.write(fHash, ref);

			// All stored cache refs identify their store, including embedded roots.
			// Rebinding is sound here because the entry was just written locally.
			ref = ref.toSoft(this);

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
		// RefCache enforces ownership on every insertion path.
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

	/**
	 * Closes this handle. Its file is retired once no older open view needs it.
	 * Closing the current handle ends writes, but retained files stay readable by
	 * older handles until they too close. Duplicate close is harmless.
	 */
	public void close() { gc.close(this); }

	/** Flushes the live destination and the files visible to this handle. */
	@Override
	public void flush() throws IOException { gc.flush(this); }

	public File getFile() {
		return etch.getFile();
	}

	@Override
	public Hash getRootHash() throws IOException { return gc.getRootHash(this); }

	/** Root changes share one lock across every open generation. */
	@Override
	public <T extends ACell> Ref<T> setRootData(T data) throws IOException {
		return gc.setRootData(this,data);
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
