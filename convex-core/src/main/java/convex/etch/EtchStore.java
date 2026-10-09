package convex.etch;

import convex.core.data.RefDirect;
import java.io.File;
import java.io.IOException;
import java.nio.channels.ClosedChannelException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Consumer;

import convex.core.data.ACell;
import convex.core.data.Cells;
import convex.core.data.Hash;
import convex.core.data.IRefFunction;
import convex.core.data.Ref;
import convex.core.exceptions.MissingDataException;
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

	/** Parallel persists share this lock; GC start drains them before publishing a target. */
	private final ReentrantReadWriteLock gcStartLock = new ReentrantReadWriteLock();

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

	/**
	 * True once the GC transfer sweep has completed for the current cycle.
	 * Sticky by design: after the sweep, live writes and root updates maintain
	 * full presence in the target (INV-1 plus the cycle write path), so the
	 * flag never needs to be recomputed. Reset by startGC/cancelGC.
	 */
	private volatile boolean sweepComplete = false;

	/**
	 * Guards against concurrent transfer sweeps (which would be wasteful and
	 * would interleave appends, destroying the DFS locality of the target).
	 */
	private final java.util.concurrent.atomic.AtomicBoolean sweepRunning = new java.util.concurrent.atomic.AtomicBoolean();

	/**
	 * True once completeGC() has cut over to a successor store. This store then
	 * remains a fully functional view (reads fall back across both files, writes
	 * route to the target — the successor's file), and the caller decides when
	 * to stop using and close it. The flag guards the operations that would be
	 * wrong afterwards: closing the successor's file, cancelling, or completing
	 * again.
	 */
	private volatile boolean completed = false;

	/**
	 * Ensures close-time file retirement runs exactly once. This matters after a
	 * completed GC cutover: a later recovery may install the successor under the
	 * legacy store's file name, which a second close must never delete.
	 */
	private final java.util.concurrent.atomic.AtomicBoolean closed = new java.util.concurrent.atomic.AtomicBoolean();

	/**
	 * The logical base file of this store, used for naming GC target files
	 * (base~, base~1, ...). Inherited across GC cutovers, and set to the
	 * requested file when a store opens on a chain tail under deferred
	 * adoption. Naming targets off the base (instead of the current physical
	 * file) keeps names bounded on long-running servers: successive cycles
	 * produce base~, base~1, base~2... rather than nesting base~~~...
	 * Parentage of chained cutovers is carried by the .gc-complete markers,
	 * not by name nesting.
	 */
	private volatile File baseFile;

	/** Resolved file and cache settings, inherited by every GC successor. */
	private final EtchConfig config;


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
		super(config.getRefCacheSize(),config.isL2Enabled());
		this.config = config;
		this.etch = etch;
		this.target = null;
		this.baseFile = etch.getFile();
		etch.setStore(this);
	}

	/** Returns the resolved configuration used by this store, including its caches. */
	public EtchConfig getConfig() {
		return config;
	}

	/**
	 * Starts a GC cycle. Creates a new target Etch file: subsequent writes are
	 * directed to the target, while reads check the target first and fall back
	 * to the old file. Waits for in-flight persists before publishing the target,
	 * so copying sees stable source statuses. See convex-core/docs/ETCH_GC.md.
	 *
	 * @throws IOException If an IO exception occurs
	 * @throws IllegalStateException if a GC cycle is already in progress/completed,
	 *         or called from a persistence callback
	 */
	public void startGC() throws IOException {
		if (gcStartLock.getReadHoldCount() != 0) {
			throw new IllegalStateException("Cannot start GC from a persistence callback");
		}
		gcStartLock.writeLock().lock();
		try {
			// Check before taking the store monitor: cancellation holds that monitor
			// while reverse migration calls storeRef (which takes the shared lock).
			if ((target != null) || completed) {
				throw new IllegalStateException("GC already active or completed for store: " + this);
			}
			startGCExclusive();
		} finally {
			gcStartLock.writeLock().unlock();
		}
	}

	private synchronized void startGCExclusive() throws IOException {
		if (completed)
			// this store is a legacy view over the successor's file: a new cycle
			// here would make no sense — GC the successor instead
			throw new IllegalStateException("GC already completed for store: " + this);
		if (target != null)
			throw new IllegalStateException("GC already in progress for store: " + this);

		// Generational target naming OFF THE BASE FILE (bounded on long-running
		// servers: base~, base~1, ... — never nested base~~). An existing target
		// file is either live/stale (must be neither adopted nor deleted —
		// recovery's concern) or defunct (tombstoned: superseded or cancelled,
		// contents verifiably elsewhere). Defunct files pinned by mappings
		// (Windows) become deletable once their buffers are garbage collected,
		// so retry their deletion here and reuse the name — this keeps
		// generation numbers small and reclaims their disk without needing a
		// process restart
		String base = baseFile.getCanonicalPath() + "~";
		File temp = new File(base);
		for (int i = 1; ; i++) {
			if (!temp.exists()) {
				// clean up any orphaned tombstone left by a partially-deferred deletion
				new File(temp.getPath() + ".gc-defunct").delete();
				break;
			}
			File tomb = new File(temp.getPath() + ".gc-defunct");
			if (!temp.getCanonicalFile().equals(etch.getFile().getCanonicalFile())
					&& tomb.exists() && temp.delete()) {
				tomb.delete();
				break; // reclaimed a defunct file's name (and its disk space)
			}
			if (i >= 100) throw new IllegalStateException("Too many stale GC target files: " + base);
			temp = new File(base + i);
		}

		// Fully initialise the target before publication: readers must never see
		// a target without its store binding and root hash
		// Preserve the store format and encryption policy while generating a new
		// v3 file salt for the independent target file.
		Etch t = Etch.create(temp,config);
		t.setStore(this);
		t.setRootHash(etch.getRootHash());
		sweepComplete = false;
		target = t;
	}

	/**
	 * Runs the GC transfer sweep: copies the tree reachable from the current
	 * root into the GC target, preserving each entry's status from the old
	 * file. Blocking — call from a background thread; safe alongside live
	 * writes (which land in the target independently).
	 *
	 * After successful completion isGCComplete() returns true, and stays true:
	 * subsequent writes and root updates maintain full presence in the target.
	 *
	 * @throws IOException in case of IO error
	 * @throws IllegalStateException if no cycle is active, the cycle is being
	 *         cancelled (including mid-sweep), or a sweep is already running
	 */
	public void transferGC() throws IOException {
		Etch t = target;
		if ((t == null) || cancelling || completed)
			throw new IllegalStateException("No active GC cycle for store: " + this);
		if (!sweepRunning.compareAndSet(false, true))
			throw new IllegalStateException("GC transfer already running for store: " + this);
		try {
			Hash rootHash = getRootHash();
			Ref<ACell> rootRef = getRootRef();
			if (rootRef == null) throw new MissingDataException(this, rootHash);
			if (rootRef.getValue() != null) {
				sweep(t, rootRef);
			}
			// Sticky completion: see sweepComplete field notes. Only claim it if
			// the cycle we swept is still the live one
			if ((target == t) && !cancelling) {
				sweepComplete = true;
			}
		} finally {
			sweepRunning.set(false);
		}
	}

	/**
	 * Iterative post-order sweep (stack-safe for arbitrarily deep trees).
	 * Children are transferred before their parent, so each per-entry persist
	 * finds its children already target-resident and recurses at most one
	 * level: the recursive storeRef machinery is safe to reuse here.
	 */
	@SuppressWarnings("unchecked")
	private void sweep(Etch t, Ref<ACell> rootRef) throws IOException {
		ArrayDeque<SweepFrame> stack = new ArrayDeque<>();
		stack.push(new SweepFrame((Ref<ACell>) rootRef));
		while (!stack.isEmpty()) {
			// The sweep must not outlive its cycle: writes would silently start
			// going elsewhere (cancel) and completion would be a false claim
			if (cancelling || (target != t))
				throw new IllegalStateException("GC cycle ended during transfer for store: " + this);

			SweepFrame f = stack.peek();
			if (!f.expanded) {
				f.expanded = true;
				Hash h = f.ref.getHash();

				// Every GC copy preserves old per-entry status in storeRef, including
				// live persists before this sweep. A target-resident persisted tree
				// therefore proves both presence (INV-1) and status transfer. This
				// also dedups shared subtrees without a visited-set.
				Ref<ACell> tgtRef = t.read(h);
				if ((tgtRef != null) && (tgtRef.getStatus() >= Ref.PERSISTED)) {
					stack.pop();
					continue;
				}

				// Post-order: expand children first
				Cells.visitBranchRefs(f.ref.getValue(),
						br -> stack.push(new SweepFrame((Ref<ACell>) br)));
			} else {
				stack.pop();
				// Children are in the target: this persist recurses one level at
				// most (each child check prunes), so the write is cheap and the
				// entry lands adjacent to its children (DFS locality)
				// The copy preserves this entry's old status independently; asking
				// for ANNOUNCED here would also promote its children.
				storeTopRef(f.ref, Ref.PERSISTED, null);
			}
		}
	}

	private static final class SweepFrame {
		final Ref<ACell> ref;
		boolean expanded;

		SweepFrame(Ref<ACell> ref) {
			this.ref = ref;
		}
	}

	/**
	 * Checks whether the current GC cycle's transfer sweep has completed. Once
	 * true, everything reachable from the current root is in the target, and
	 * stays that way (live writes maintain the guarantee).
	 *
	 * @return true if a cycle is in progress and its sweep has completed
	 */
	public boolean isGCComplete() {
		return (target != null) && !cancelling && !completed && sweepComplete;
	}

	/**
	 * Verifies GC transfer completeness against the target file ONLY (the
	 * store's own reads fall back to the old file, so they cannot be used).
	 * Belt-and-braces independent check: full walk, no INV-1 pruning.
	 *
	 * @return list of hashes reachable from the current root but missing from
	 *         the GC target; empty means the transfer is complete
	 * @throws IOException in case of IO error
	 * @throws IllegalStateException if no GC cycle is in progress
	 */
	public java.util.List<Hash> verifyGC() throws IOException {
		Etch t = target;
		if ((t == null) || completed)
			throw new IllegalStateException("No GC in progress for store: " + this);
		return EtchUtils.verify(t, getRootHash());
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
		if (completed)
			// the target is the successor's live file: reverse-migrating it would
			// be catastrophic
			throw new IllegalStateException("GC already completed for store: " + this);
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
		sweepComplete = false;
		t.close();
		File tf = t.getFile();
		if (!tf.delete()) {
			tf.deleteOnExit();
			// Tombstone: the target's contents are fully rolled back, so startup
			// recovery must retry the deletion but NEVER roll it back again
			// (re-rolling superseded data into a later, collected store would
			// re-introduce garbage)
			File tomb = new File(tf.getCanonicalPath() + ".gc-defunct");
			java.nio.file.Files.writeString(tomb.toPath(), "rolled back by cancelGC\n");
			tomb.deleteOnExit();
		}
	}

	/**
	 * Completes the GC cycle: cuts over to a new EtchStore wrapping the target
	 * file, and returns it. Whether and when to stop using this (old) store is
	 * the caller's decision: it remains a fully functional view — reads fall
	 * back across both files, writes route to the successor's file — until
	 * closed. All transferred values are retrievable by hash from the
	 * successor; refs bound to this store stop resolving once it is closed.
	 *
	 * Requires a completed sweep and a fresh target-only verification of the
	 * current root. There is no force override. Close predecessor views before
	 * collecting the successor; legacy writes do not follow further generations.
	 *
	 * @return the successor EtchStore, running on the (former) target file
	 * @throws IOException in case of IO error
	 * @throws MissingDataException if the current root tree is absent from the target
	 * @throws IllegalStateException if no active cycle, cancelling, already
	 *         completed, or the sweep has not completed
	 */
	public synchronized EtchStore completeGC() throws IOException {
		return completeGC(null);
	}

	/**
	 * Completes GC, optionally retaining the original file as a backup snapshot.
	 * The backup is a hard link: no data copy is needed, and it survives deletion
	 * of the original name. It must be a new path on the same filesystem, outside
	 * the store's GC filenames. Close this legacy store before opening the backup;
	 * its root is the pre-cycle root, not a root updated during collection.
	 * Any persists begun before startGC must finish before the legacy store closes.
	 * Retaining a backup keeps the old disk space allocated. On Windows, hard links
	 * require the same NTFS volume. This overload has the same verification and
	 * predecessor-lifetime requirements as {@link #completeGC()}.
	 *
	 * @param backupFile backup path, or null to discard the original as usual
	 * @return the successor store
	 * @throws IOException if verification, backup creation or cutover fails
	 * @throws MissingDataException if the current root tree is absent from the target
	 */
	public synchronized EtchStore completeGC(File backupFile) throws IOException {
		Etch t = target;
		if (completed)
			throw new IllegalStateException("GC already completed for store: " + this);
		if ((t == null) || cancelling)
			throw new IllegalStateException("No active GC cycle for store: " + this);
		if (!sweepComplete)
			throw new IllegalStateException("GC transfer not complete for store: " + this);
		java.util.List<Hash> missing = EtchUtils.verify(t, t.getRootHash());
		if (!missing.isEmpty()) throw new MissingDataException(this, missing.get(0));

		// Everything must be durable in the target before we commit to it
		t.flush();
		Path backup = null;
		if (backupFile != null) {
			backup = validateGCBackup(backupFile).toPath();
			etch.flush();
			Files.createLink(backup, etch.getFile().toPath());
		}

		// Tombstone the superseded old file FIRST: its retained content is
		// verifiably in the successor, so recovery may delete it but must never
		// roll it back (that would re-introduce collected garbage). Written
		// before the marker: a crash between the two reads as "cutover didn't
		// happen" (marker still names this file; the target rolls back), which
		// loses nothing
		Path tomb = new File(etch.getFile().getCanonicalPath() + ".gc-defunct").toPath();
		try {
			EtchUtils.writeMetadata(tomb, "superseded by " + t.getFile().getName() + "\n");
			EtchUtils.writeMarker(baseFile, t.getFile());
		} catch (IOException e) {
			// The atomic marker replacement failed: this store still owns both
			// files and can retry or cancel. Do not leave a mutable backup behind.
			try { Files.deleteIfExists(tomb); } catch (IOException cleanup) { e.addSuppressed(cleanup); }
			if (backup != null) {
				try { Files.deleteIfExists(backup); } catch (IOException cleanup) { e.addSuppressed(cleanup); }
			}
			throw e;
		}

		// Publish ownership only after the on-disk cutover succeeds. The target
		// is rebound to the successor, whose refs outlive this store's close.
		EtchStore newStore = new EtchStore(t);
		newStore.baseFile = this.baseFile;
		completed = true;

		return newStore;
	}

	/**
	 * Checks a proposed backup path without creating or modifying any file.
	 * @param backupFile proposed backup file
	 * @return canonical backup file
	 * @throws IOException if the path is occupied or conflicts with GC files
	 */
	public File validateGCBackup(File backupFile) throws IOException {
		File backup = backupFile.getCanonicalFile();
		File base = baseFile.getCanonicalFile();
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
	public boolean isGCInProgress() {
		return (target != null) && !completed;
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
		store.baseFile = file;
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
		return baseFile;
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
		// During a GC cycle, new writes land in the target: check it first, then
		// fall back to the old file for data not yet migrated. Outside a cycle
		// this costs a single volatile load and an untaken branch
		Etch t = target;
		if (t != null) {
			try {
				Ref<T> ref = t.read(hash);
				if (ref != null) {
					// After completeGC() the target belongs to the successor, so
					// refs decode bound to it: serve them (they outlive this
					// store's close) but never cache foreign-bound refs
					if (!isForeign(ref)) refCache.putCell(ref);
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
		gcStartLock.readLock().lock();
		try {
			return storeRefToCurrentFile(ref, requiredStatus, noveltyHandler, topLevel);
		} finally {
			gcStartLock.readLock().unlock();
		}
	}

	private <T extends ACell> Ref<T> storeRefToCurrentFile(Ref<T> ref, int requiredStatus,
			Consumer<Ref<ACell>> noveltyHandler, boolean topLevel) throws IOException {
		// The shared GC-start lock lets persists run concurrently but prevents
		// source status upgrades from outliving cycle start. Snapshot the write
		// file once for the entire descent: splitting a tree across files would
		// break the target's subtree-presence guarantee.
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
			Ref<T> oldRef = etch.read(hash);
			if (oldRef != null) {
				writeStatus = Math.max(writeStatus, Math.min(oldRef.getStatus(), Ref.MAX_STATUS));
			}
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
					return storeRef((Ref<ACell>) r, childStatus, noveltyHandler, false, writeEtch);
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

	public void close() {
		if (!closed.compareAndSet(false, true)) return;
		// Close the GC target too if a cycle is in progress. This abandons the
		// cycle: data written since startGC() remains in the target file for a
		// later recovery step, and the old file is untouched. After completeGC()
		// the target belongs to the successor store and must NOT be closed here
		Etch t = target;
		if ((t != null) && !completed)
			t.close();
		etch.close();
		if (completed) {
			// Cutover done and this legacy view is now closed: the old file's
			// retained content is verifiably in the successor (completeGC is
			// hard-gated on a complete sweep) and everything else is garbage by
			// the retention contract. Removing this name reclaims disk space
			// unless a requested backup still retains the file through a hard link. If
			// mappings pin the file (Windows), deletion is retried on JVM exit
			// and by startup recovery, guided by the .gc-defunct tombstone
			// completeGC already wrote
			File f = etch.getFile();
			if (f.delete()) {
				new File(f.getPath() + ".gc-defunct").delete();
			} else {
				f.deleteOnExit();
			}
		}
	}

	/**
	 * Ensure the store is fully persisted to disk
	 * 
	 * @throws IOException If an IO error occurs
	 */
	@Override
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
