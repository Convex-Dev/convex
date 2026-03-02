package convex.lattice.queue;

import convex.core.data.ACell;
import convex.core.data.AHashMap;
import convex.core.data.AVector;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;
import convex.lattice.cursor.ALatticeCursor;
import convex.lattice.cursor.Cursors;

/**
 * A high-performance distributed queue partition built on Convex lattice technology.
 *
 * <p>Provides Kafka-style append-only log semantics: items are appended with
 * monotonically increasing offsets, and consumers track their own read positions
 * independently. Supports truncation for reclaiming space while preserving
 * offset continuity.</p>
 *
 * <p>Use {@link #fork()} and {@link #sync()} for conflict-free distributed
 * replication with lattice merge semantics.</p>
 *
 * <h2>Concurrency</h2>
 * <ul>
 *   <li>Thread-safe via cursor CAS operations</li>
 *   <li>Multiple threads on same node: safe (CAS retry)</li>
 *   <li>Single-writer per queue recommended for distributed operation</li>
 * </ul>
 */
public class LatticeQueue {

	private final ALatticeCursor<AVector<ACell>> cursor;

	public LatticeQueue(ALatticeCursor<AVector<ACell>> cursor) {
		this.cursor = cursor;
	}

	/**
	 * Creates a new empty queue.
	 */
	public static LatticeQueue create() {
		ALatticeCursor<AVector<ACell>> cursor = Cursors.createLattice(QueueLattice.INSTANCE);
		return new LatticeQueue(cursor);
	}

	/**
	 * Returns the underlying lattice cursor.
	 */
	public ALatticeCursor<AVector<ACell>> cursor() {
		return cursor;
	}

	/**
	 * Creates a forked copy of this queue for independent operation.
	 */
	public LatticeQueue fork() {
		return new LatticeQueue(cursor.fork());
	}

	/**
	 * Syncs this forked queue back to its parent, merging changes.
	 */
	public void sync() {
		cursor.sync();
	}

	// ===== Producer Operations =====

	/**
	 * Appends a value to the queue with automatic timestamp.
	 *
	 * @param value Record value (must not be null)
	 * @return Absolute offset assigned to the entry
	 */
	public long offer(ACell value) {
		return offer(null, value, null);
	}

	/**
	 * Appends a keyed record to the queue with automatic timestamp.
	 *
	 * @param key Record key (may be null)
	 * @param value Record value (must not be null)
	 * @return Absolute offset assigned to the entry
	 */
	public long offer(ACell key, ACell value) {
		return offer(key, value, null);
	}

	/**
	 * Appends a full Kafka-style record to the queue.
	 *
	 * @param key Record key (may be null)
	 * @param value Record value (must not be null)
	 * @param headers Record headers (may be null)
	 * @return Absolute offset assigned to the entry
	 */
	public long offer(ACell key, ACell value, AHashMap<ACell, ACell> headers) {
		if (value == null) {
			throw new IllegalArgumentException("Queue value must not be null");
		}

		CVMLong now = now();
		AVector<ACell> entry = QueueEntry.create(key, value, now, headers);

		long[] offset = new long[1];
		cursor.updateAndGet(state -> {
			if (state == null) state = QueueLattice.INSTANCE.zero();

			AVector<ACell> entries = QueueLattice.getEntries(state);
			long startOffset = QueueLattice.getStartOffset(state);
			AHashMap<ACell, ACell> meta = QueueLattice.getMeta(state);

			AVector<ACell> newEntries = entries.append(entry);
			offset[0] = startOffset + newEntries.count() - 1;

			return Vectors.of(newEntries, meta, now, state.get(QueueLattice.POS_START_OFFSET));
		});

		return offset[0];
	}

	// ===== Consumer Operations =====

	/**
	 * Returns the full entry record at the specified absolute offset, or null if not found.
	 */
	@SuppressWarnings("unchecked")
	public AVector<ACell> peekEntry(long offset) {
		AVector<ACell> state = cursor.get();
		if (state == null) return null;

		AVector<ACell> entries = QueueLattice.getEntries(state);
		long startOffset = QueueLattice.getStartOffset(state);

		long index = offset - startOffset;
		if (index < 0 || index >= entries.count()) return null;
		return (AVector<ACell>) entries.get(index);
	}

	/**
	 * Returns the value at the specified absolute offset, or null if not found.
	 */
	public ACell peek(long offset) {
		AVector<ACell> entry = peekEntry(offset);
		return QueueEntry.getValue(entry);
	}

	/**
	 * Returns the first value in the queue, or null if empty.
	 */
	public ACell peekFirst() {
		AVector<ACell> entry = peekFirstEntry();
		return QueueEntry.getValue(entry);
	}

	/**
	 * Returns the last value in the queue, or null if empty.
	 */
	public ACell peekLast() {
		AVector<ACell> entry = peekLastEntry();
		return QueueEntry.getValue(entry);
	}

	/**
	 * Returns the first entry in the queue, or null if empty.
	 */
	@SuppressWarnings("unchecked")
	public AVector<ACell> peekFirstEntry() {
		AVector<ACell> state = cursor.get();
		if (state == null) return null;
		AVector<ACell> entries = QueueLattice.getEntries(state);
		if (entries.isEmpty()) return null;
		return (AVector<ACell>) entries.get(0);
	}

	/**
	 * Returns the last entry in the queue, or null if empty.
	 */
	@SuppressWarnings("unchecked")
	public AVector<ACell> peekLastEntry() {
		AVector<ACell> state = cursor.get();
		if (state == null) return null;
		AVector<ACell> entries = QueueLattice.getEntries(state);
		if (entries.isEmpty()) return null;
		return (AVector<ACell>) entries.get(entries.count() - 1);
	}

	// ===== Queue Info =====

	/**
	 * Returns the first valid absolute offset, or 0 if empty.
	 */
	public long startOffset() {
		AVector<ACell> state = cursor.get();
		return QueueLattice.getStartOffset(state);
	}

	/**
	 * Returns the next absolute offset to be written (exclusive end).
	 */
	public long endOffset() {
		AVector<ACell> state = cursor.get();
		if (state == null) return 0L;
		return QueueLattice.getStartOffset(state) + QueueLattice.getEntries(state).count();
	}

	/**
	 * Returns the number of entries currently in the queue.
	 */
	public long size() {
		AVector<ACell> state = cursor.get();
		if (state == null) return 0L;
		return QueueLattice.getEntries(state).count();
	}

	/**
	 * Returns true if the queue is empty.
	 */
	public boolean isEmpty() {
		return size() == 0L;
	}

	// ===== Range =====

	/**
	 * Returns a vector of values in the specified absolute offset range [fromOffset, toOffset] (inclusive).
	 */
	@SuppressWarnings("unchecked")
	public AVector<ACell> range(long fromOffset, long toOffset) {
		AVector<ACell> state = cursor.get();
		if (state == null) return Vectors.empty();
		if (fromOffset > toOffset) return Vectors.empty();

		AVector<ACell> entries = QueueLattice.getEntries(state);
		long startOffset = QueueLattice.getStartOffset(state);
		long endOffset = startOffset + entries.count();

		// Clamp to valid bounds
		long from = Math.max(fromOffset, startOffset);
		long to = Math.min(toOffset, endOffset - 1);
		if (from > to) return Vectors.empty();

		long fromIdx = from - startOffset;
		long toIdx = to - startOffset + 1; // exclusive for slice

		AVector<ACell> slice = entries.slice(fromIdx, toIdx);

		// Extract values from entries
		AVector<ACell> result = Vectors.empty();
		long n = slice.count();
		for (long i = 0; i < n; i++) {
			AVector<ACell> entry = (AVector<ACell>) slice.get(i);
			result = result.append(QueueEntry.getValue(entry));
		}
		return result;
	}

	// ===== Metadata =====

	/**
	 * Gets a metadata value by key.
	 */
	public ACell getMeta(ACell key) {
		AVector<ACell> state = cursor.get();
		if (state == null) return null;
		AHashMap<ACell, ACell> meta = QueueLattice.getMeta(state);
		return meta.get(key);
	}

	/**
	 * Sets a metadata value.
	 */
	public void setMeta(ACell key, ACell value) {
		CVMLong now = now();
		cursor.updateAndGet(state -> {
			if (state == null) state = QueueLattice.INSTANCE.zero();
			AHashMap<ACell, ACell> meta = QueueLattice.getMeta(state);
			AHashMap<ACell, ACell> newMeta = meta.assoc(key, value);
			return state.assoc(QueueLattice.POS_META, newMeta).assoc(QueueLattice.POS_TIMESTAMP, now);
		});
	}

	// ===== Truncation =====

	/**
	 * Truncates the queue by advancing the start offset and removing leading entries.
	 *
	 * <p>Only advances forward; a newStartOffset less than or equal to the current
	 * start offset is a no-op.</p>
	 *
	 * @param newStartOffset New absolute start offset
	 * @return Number of entries removed
	 */
	public long truncate(long newStartOffset) {
		long[] removed = new long[1];
		CVMLong now = now();
		cursor.updateAndGet(state -> {
			if (state == null) state = QueueLattice.INSTANCE.zero();

			long currentStart = QueueLattice.getStartOffset(state);
			if (newStartOffset <= currentStart) {
				removed[0] = 0;
				return state;
			}

			AVector<ACell> entries = QueueLattice.getEntries(state);
			long trim = newStartOffset - currentStart;

			AVector<ACell> newEntries;
			if (trim >= entries.count()) {
				removed[0] = entries.count();
				newEntries = Vectors.empty();
			} else {
				removed[0] = trim;
				newEntries = entries.slice(trim);
			}

			AHashMap<ACell, ACell> meta = QueueLattice.getMeta(state);
			return Vectors.of(newEntries, meta, now, CVMLong.create(newStartOffset));
		});
		return removed[0];
	}

	// ===== Internal =====

	private CVMLong now() {
		return CVMLong.create(System.currentTimeMillis());
	}
}
