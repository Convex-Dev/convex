package convex.db.store;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;

import convex.core.data.ACell;
import convex.core.data.Hash;
import convex.core.data.Ref;
import convex.core.store.ACachedStore;
import convex.etch.EtchStore;

/**
 * A tiered store backed by a directory of Etch segment files.
 *
 * <p>Layout inside the directory:
 * <pre>
 *   hot.etch                    — active write target (always present)
 *   warm-YYYYMMDD-HHmmss.etch   — sealed segment, not yet compacted
 *   warm-YYYYMMDD-HHmmss-c.etch — sealed segment, already compacted
 * </pre>
 *
 * <p>Writes always target the hot segment. Reads cascade: hot first, then sealed
 * segments in reverse-timestamp order (newest first), stopping at first hit.
 *
 * <p>Call {@link #sealHot()} to rotate the hot segment into a warm segment and open
 * a fresh hot file. Sealed segments are immutable after creation.
 *
 * <p>Call {@link #compactSealed(int)} or {@link #compactAllSealed()} to compact warm
 * segments online (no server downtime required — warm files are never written to).
 * Compacted segments are renamed with a {@code -c} suffix so they are not compacted again.
 *
 * <p>Note: when storeRef recurses into child cells, it checks only the hot segment's
 * local index, so cells already present in a sealed segment may be written again to hot.
 * This is harmless duplication; a compaction pass on the hot segment will deduplicate.
 */
public class SegmentedEtchStore extends ACachedStore {

	private static final String HOT_NAME = "hot.etch";
	private static final String COMPACTED_SUFFIX = "-c";
	private static final DateTimeFormatter TIMESTAMP_FMT =
			DateTimeFormatter.ofPattern("yyyyMMdd-HHmmssSSS");

	private final File directory;
	/** Active write target. */
	private EtchStore hot;
	/** Sealed read-only segments, index 0 = oldest, last index = newest. */
	private final List<EtchStore> sealed;

	private SegmentedEtchStore(File dir, EtchStore hot, List<EtchStore> sealed) {
		this.directory = dir;
		this.hot = hot;
		this.sealed = sealed;
	}

	// ── Factory methods ───────────────────────────────────────────────────────

	/**
	 * Opens (or creates) a segmented store rooted at {@code dir}.
	 * Creates the directory and a fresh {@code hot.etch} if they do not exist.
	 * Any existing {@code warm-*.etch} files are opened as sealed segments.
	 *
	 * @param dir Directory for this segmented store
	 * @return New SegmentedEtchStore instance
	 * @throws IOException on IO error
	 */
	public static SegmentedEtchStore open(File dir) throws IOException {
		dir.mkdirs();
		// Accept both warm-*.etch and warm-*-c.etch (compacted)
		File[] warmFiles = dir.listFiles((d, n) -> n.startsWith("warm-") && n.endsWith(".etch"));
		List<EtchStore> sealed = new ArrayList<>();
		if (warmFiles != null) {
			Arrays.sort(warmFiles, Comparator.comparing(File::getName));
			for (File f : warmFiles) sealed.add(EtchStore.create(f));
		}
		EtchStore hot = EtchStore.create(new File(dir, HOT_NAME));
		return new SegmentedEtchStore(dir, hot, sealed);
	}

	/**
	 * Creates a fresh segmented store: removes all existing {@code *.etch} files in
	 * {@code dir}, then opens a single empty hot segment.
	 * Use this at the start of each benchmark run to ensure a clean state.
	 *
	 * @param dir Directory for this segmented store
	 * @return New empty SegmentedEtchStore instance
	 * @throws IOException on IO error
	 */
	public static SegmentedEtchStore createFresh(File dir) throws IOException {
		dir.mkdirs();
		File[] existing = dir.listFiles((d, n) -> n.endsWith(".etch"));
		if (existing != null) for (File f : existing) f.delete();
		return open(dir);
	}

	// ── Segment rotation ──────────────────────────────────────────────────────

	/**
	 * Seals the current hot segment and opens a new empty hot segment.
	 *
	 * <p>The current {@code hot.etch} is flushed, closed, and renamed to
	 * {@code warm-{timestamp}.etch}. A fresh empty {@code hot.etch} is then opened
	 * as the new write target. The sealed segment becomes the newest in the
	 * read-fallback chain.
	 *
	 * @throws IOException on IO error
	 */
	public synchronized void sealHot() throws IOException {
		hot.flush();
		hot.close();
		String ts = LocalDateTime.now().format(TIMESTAMP_FMT);
		File oldHot = new File(directory, HOT_NAME);
		File warmFile = new File(directory, "warm-" + ts + ".etch");
		Files.move(oldHot.toPath(), warmFile.toPath());
		sealed.add(EtchStore.create(warmFile)); // newest = last
		hot = EtchStore.create(new File(directory, HOT_NAME));
	}

	// ── Read path ─────────────────────────────────────────────────────────────

	@Override
	public <T extends ACell> Ref<T> refForHash(Hash hash) {
		// 1. Shared in-memory cache (avoids redundant disk reads across all segments)
		Ref<T> cached = checkCache(hash);
		if (cached != null) return cached;

		// 2. Hot segment (most likely for recent writes)
		Ref<T> r = hot.refForHash(hash);
		if (r != null) return r;

		// 3. Sealed segments — newest first (most likely location for recent history)
		for (int i = sealed.size() - 1; i >= 0; i--) {
			r = sealed.get(i).refForHash(hash);
			if (r != null) return r;
		}
		return null;
	}

	// ── Write path ────────────────────────────────────────────────────────────

	@Override
	public <T extends ACell> Ref<T> storeRef(Ref<T> ref, int status,
			Consumer<Ref<ACell>> noveltyHandler) throws IOException {
		return hot.storeRef(ref, status, noveltyHandler);
	}

	@Override
	public <T extends ACell> Ref<T> storeTopRef(Ref<T> ref, int status,
			Consumer<Ref<ACell>> noveltyHandler) throws IOException {
		return hot.storeTopRef(ref, status, noveltyHandler);
	}

	// ── Root management ───────────────────────────────────────────────────────

	@Override
	public Hash getRootHash() throws IOException {
		return hot.getRootHash();
	}

	@Override
	public <T extends ACell> Ref<T> setRootData(T data) throws IOException {
		return hot.setRootData(data);
	}

	// ── Lifecycle ─────────────────────────────────────────────────────────────

	/** Flushes the hot segment to disk. */
	public void flush() throws IOException {
		hot.flush();
	}

	@Override
	public void close() {
		hot.close();
		for (EtchStore s : sealed) s.close();
	}

	// ── Sealed segment compaction ─────────────────────────────────────────────

	/**
	 * Returns true if the sealed segment at {@code index} has already been compacted
	 * (its filename ends with {@code -c.etch}).
	 *
	 * @param index Index into the sealed segment list (0 = oldest)
	 * @return true if already compacted
	 */
	public boolean isCompacted(int index) {
		File f = sealed.get(index).getFile();
		String name = f.getName();
		return name.endsWith(COMPACTED_SUFFIX + ".etch");
	}

	/**
	 * Returns true if the sealed segment at {@code index} needs compaction.
	 * Equivalent to {@code !isCompacted(index)}.
	 *
	 * @param index Index into the sealed segment list (0 = oldest)
	 * @return true if compaction has not been run on this segment
	 */
	public boolean needsCompaction(int index) {
		return !isCompacted(index);
	}

	/**
	 * Compacts the sealed segment at {@code index} in place.
	 *
	 * <p>Writes a compacted copy to a temporary file alongside the original, then
	 * atomically renames it to the compacted name ({@code warm-TIMESTAMP-c.etch}).
	 * The old segment file is deleted. The in-memory {@code sealed} list is updated
	 * to point at the new file. No server downtime required.
	 *
	 * <p>If the segment is already compacted this is a no-op.
	 *
	 * @param index Index into the sealed segment list (0 = oldest)
	 * @throws IOException on IO error
	 */
	public synchronized void compactSealed(int index) throws IOException {
		if (isCompacted(index)) return;

		EtchStore src = sealed.get(index);
		File srcFile = src.getFile();

		// Derive compacted filename: strip .etch, append -c.etch
		String base = srcFile.getName();
		base = base.substring(0, base.length() - ".etch".length());
		File tmpFile = new File(directory, base + "-c.tmp.etch");
		File dstFile = new File(directory, base + COMPACTED_SUFFIX + ".etch");

		if (tmpFile.exists()) tmpFile.delete();

		EtchStore compacted = src.compact(tmpFile);
		compacted.close();

		// Atomic swap: rename tmp → dst, delete old src, reload segment
		Files.move(tmpFile.toPath(), dstFile.toPath());
		src.close();
		srcFile.delete();

		sealed.set(index, EtchStore.create(dstFile));
	}

	/**
	 * Compacts all sealed segments that have not yet been compacted.
	 * Segments are processed oldest-first. Already-compacted segments are skipped.
	 *
	 * @throws IOException on IO error
	 */
	public void compactAllSealed() throws IOException {
		for (int i = 0; i < sealed.size(); i++) {
			if (needsCompaction(i)) compactSealed(i);
		}
	}

	// ── Accessors ─────────────────────────────────────────────────────────────

	/** Returns the directory backing this store. */
	public File getDirectory() { return directory; }

	/** Returns the active hot EtchStore. */
	public EtchStore getHot() { return hot; }

	/** Returns an unmodifiable view of the sealed segment stores, oldest first. */
	public List<EtchStore> getSealed() { return Collections.unmodifiableList(sealed); }

	/** Returns the file path of the current hot segment. */
	public File getHotFile() { return new File(directory, HOT_NAME); }

	/** Returns the number of sealed segments. */
	public int getSealedCount() { return sealed.size(); }

	@Override
	public String shortName() { return "Segmented:" + directory.getName(); }

	@Override
	public boolean isPersistent() { return true; }
}
