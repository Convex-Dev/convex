package convex.db.lattice;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;

import convex.core.data.AArrayBlob;
import convex.core.data.ABlob;
import convex.core.data.ACell;
import convex.core.data.AVector;
import convex.core.data.Blob;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;

/**
 * Utility class for prefix-keyed row blocks.
 *
 * <p>Since v4, a block is stored as a flat {@link Blob} with binary layout:
 * <pre>
 *   [4 bytes big-endian: count N]
 *   [N × 4 bytes: entry byte-offsets from start of DATA section]
 *   DATA section:
 *     entry[i]:
 *       [1 byte: pk_len]
 *       [pk_len bytes: pk]
 *       [1 byte: flags — 0=live, 1=tombstone]
 *       [8 bytes: utime (write-sequence number, big-endian long)]
 *       if flags==0:
 *         [2 bytes: values_len]
 *         [values_len bytes: CAD3-encoded values]
 * </pre>
 *
 * <p>Legacy v1 AVector format ({@code [CVMLong(N), pk0, row0, ...]}) is read-only
 * for backward compatibility; writes always produce flat Blobs.
 *
 * <p>Block key in the outer Index = first {@link #PREFIX_BYTES} bytes of the PK.
 * Default is 8, increased from 6 to avoid degenerate single-block scenarios with
 * sequential integer PKs.
 */
public class RowBlock {

	/** Number of PK prefix bytes used as the block's Index key. */
	public static final int PREFIX_BYTES = Integer.getInteger("convex.block.prefix", 8);

	/** Byte size of the flat block header (just the count field). */
	private static final int HDR = 4;

	private RowBlock() {}

	// ── Low-level int helpers ─────────────────────────────────────────────────

	private static int rInt(byte[] b, int off) {
		return ((b[off]&0xFF)<<24)|((b[off+1]&0xFF)<<16)|((b[off+2]&0xFF)<<8)|(b[off+3]&0xFF);
	}

	private static void wInt(byte[] b, int off, int v) {
		b[off]=(byte)(v>>>24); b[off+1]=(byte)(v>>>16); b[off+2]=(byte)(v>>>8); b[off+3]=(byte)v;
	}

	/** Absolute byte position of the DATA section for a block with N entries. */
	private static int dataStart(int n) { return HDR + 4 * n; }

	// ── Block key ─────────────────────────────────────────────────────────────

	/**
	 * Returns the block Index key for a given primary key: the first
	 * {@link #PREFIX_BYTES} bytes of {@code pk}, or {@code pk} itself if shorter.
	 */
	public static ABlob blockKey(ABlob pk) {
		int plen = (int) pk.count();
		if (plen <= PREFIX_BYTES) return pk;
		byte[] bytes = new byte[PREFIX_BYTES];
		for (int i = 0; i < PREFIX_BYTES; i++) bytes[i] = pk.byteAtUnchecked(i);
		return Blob.wrap(bytes);
	}

	// ── Detection ─────────────────────────────────────────────────────────────

	/**
	 * Returns true if block is a RowBlock (flat Blob v4 or legacy AVector v1).
	 */
	public static boolean isBlock(ACell block) {
		if (block instanceof Blob) return true;
		if (!(block instanceof AVector)) return false;
		@SuppressWarnings("unchecked") AVector<ACell> v = (AVector<ACell>) block;
		if (v.count() < 1) return false;
		ACell first = v.get(0);
		if (!(first instanceof CVMLong)) return false;
		long n = ((CVMLong) first).longValue();
		if (n < 0) return false;
		return v.count() == 1 + 2 * n;
	}

	// ── Count ─────────────────────────────────────────────────────────────────

	/** Number of rows (live + tombstone) in this block. Returns 0 for null or non-block values. */
	public static int count(ACell block) {
		if (block instanceof AArrayBlob) {
			AArrayBlob b = (AArrayBlob) block;
			if (b.count() < 4) return 0;
			byte[] bs = b.getInternalArray();
			int base = b.getInternalOffset();
			return rInt(bs, base);
		}
		if (!(block instanceof AVector)) return 0;
		@SuppressWarnings("unchecked") AVector<ACell> v = (AVector<ACell>) block;
		if (!isBlock(v)) return 0;
		return (int)((CVMLong)v.get(0)).longValue();
	}

	// ── pk-comparison on raw bytes ────────────────────────────────────────────

	/** Compare the stored pk at entry position entryStart (in bs) with the given pk. */
	private static int cmpAt(byte[] bs, int entryStart, ABlob pk) {
		int sLen = bs[entryStart] & 0xFF;
		int gLen = (int) pk.count();
		int min  = Math.min(sLen, gLen);
		for (int i = 0; i < min; i++) {
			int c = (bs[entryStart+1+i]&0xFF) - (pk.byteAtUnchecked(i)&0xFF);
			if (c != 0) return c;
		}
		return sLen - gLen;
	}

	/**
	 * Binary search in the full flat-block byte array.
	 * @param base absolute offset of the block's first byte within bs
	 * @return index if found, or -(insertionPoint+1) if not found
	 */
	private static int bsearch(byte[] bs, int n, int base, int ds, ABlob pk) {
		int lo = 0, hi = n - 1;
		while (lo <= hi) {
			int mid = (lo + hi) >>> 1;
			int off = rInt(bs, base + HDR + 4 * mid);
			int cmp = cmpAt(bs, ds + off, pk);
			if (cmp == 0) return mid;
			if (cmp < 0) lo = mid + 1; else hi = mid - 1;
		}
		return -(lo + 1);
	}

	// ── Row decode from flat bytes ────────────────────────────────────────────

	/**
	 * Decode a SQLRow from flat bytes. afterPkPos points to: flags(1) utime(8) [valLen(2) val(*)].
	 */
	private static AVector<ACell> decodeRow(byte[] bs, int afterPkPos) {
		int flags = bs[afterPkPos] & 0xFF;
		byte[] u = new byte[8];
		System.arraycopy(bs, afterPkPos + 1, u, 0, 8);
		Blob utime = Blob.wrap(u);
		if (flags != 0) return Vectors.of(null, utime, utime); // tombstone
		int vLen = ((bs[afterPkPos+9]&0xFF)<<8)|(bs[afterPkPos+10]&0xFF);
		byte[] v = new byte[vLen];
		System.arraycopy(bs, afterPkPos + 11, v, 0, vLen);
		return Vectors.of(Blob.wrap(v), utime);
	}

	// ── Entry encoder ─────────────────────────────────────────────────────────

	private static byte[] encodeEntry(ABlob pk, AVector<ACell> row) {
		int pkLen = (int) pk.count();
		boolean live = SQLRow.isLive(row);

		byte[] pkB = new byte[pkLen];
		pk.getBytes(pkB, 0);

		// utime (8 bytes in flat format; normalise legacy 4-byte blobs and CVMLong)
		byte[] utB = new byte[8];
		ACell utCell = row.get(SQLRow.POS_UTIME);
		if (utCell instanceof ABlob) {
			ABlob utBlob = (ABlob) utCell;
			if (utBlob.count() == 8) {
				utBlob.getBytes(utB, 0);
			} else {
				// legacy 4-byte compact seconds → decode and re-encode as 8-byte long
				long ms = SQLRow.decodeTimestampMs(utBlob);
				convex.core.util.Utils.writeLong(utB, 0, ms);
			}
		} else if (utCell instanceof CVMLong) {
			convex.core.util.Utils.writeLong(utB, 0, ((CVMLong)utCell).longValue());
		}

		byte[] vB = new byte[0];
		if (live) {
			ACell vc = row.get(SQLRow.POS_VALUES);
			if (vc instanceof ABlob) {
				int vl = (int)((ABlob)vc).count();
				vB = new byte[vl];
				((ABlob)vc).getBytes(vB, 0);
			} else if (vc instanceof AVector) {
				@SuppressWarnings("unchecked") Blob enc = SQLRow.encodeValues((AVector<ACell>)vc);
				vB = new byte[(int)enc.count()];
				enc.getBytes(vB, 0);
			}
		}

		int sz = 1 + pkLen + 1 + 8 + (live ? 2 + vB.length : 0);
		byte[] e = new byte[sz];
		int p = 0;
		e[p++] = (byte) pkLen;
		System.arraycopy(pkB, 0, e, p, pkLen); p += pkLen;
		e[p++] = live ? (byte)0 : (byte)1;
		System.arraycopy(utB, 0, e, p, 8); p += 8;
		if (live) {
			e[p++] = (byte)(vB.length>>>8); e[p++] = (byte)vB.length;
			System.arraycopy(vB, 0, e, p, vB.length);
		}
		return e;
	}

	// ── Flat Blob builder ─────────────────────────────────────────────────────

	private static Blob buildFlat(List<ABlob> pks, List<AVector<ACell>> rows) {
		int n = pks.size();
		byte[][] enc = new byte[n][];
		int total = 0;
		for (int i = 0; i < n; i++) {
			enc[i] = encodeEntry(pks.get(i), rows.get(i));
			total += enc[i].length;
		}
		byte[] bs = new byte[HDR + 4*n + total];
		wInt(bs, 0, n);
		int dp = HDR + 4*n, off = 0;
		for (int i = 0; i < n; i++) {
			wInt(bs, HDR + 4*i, off);
			System.arraycopy(enc[i], 0, bs, dp, enc[i].length);
			dp += enc[i].length;
			off += enc[i].length;
		}
		return Blob.wrap(bs);
	}

	// ── Extract all entries ───────────────────────────────────────────────────

	@SuppressWarnings("unchecked")
	private static void extractAll(ACell block, List<ABlob> pksOut, List<AVector<ACell>> rowsOut) {
		if (block instanceof AArrayBlob) {
			AArrayBlob blob = (AArrayBlob) block;
			byte[] bs = blob.getInternalArray();
			int base = blob.getInternalOffset();
			int n = rInt(bs, base);
			int ds = base + dataStart(n);
			for (int i = 0; i < n; i++) {
				int off = rInt(bs, base + HDR + 4*i);
				int es = ds + off;
				int pkLen = bs[es] & 0xFF;
				byte[] pkB = new byte[pkLen];
				System.arraycopy(bs, es+1, pkB, 0, pkLen);
				pksOut.add(Blob.wrap(pkB));
				rowsOut.add(decodeRow(bs, es + 1 + pkLen));
			}
			return;
		}
		if (!(block instanceof AVector)) return;
		AVector<ACell> v = (AVector<ACell>) block;
		if (!isBlock(v)) return;
		int n = (int)((CVMLong)v.get(0)).longValue();
		for (int i = 0; i < n; i++) {
			pksOut.add((ABlob) v.get(1 + 2*i));
			rowsOut.add((AVector<ACell>) v.get(2 + 2*i));
		}
	}

	// ── Point lookup ──────────────────────────────────────────────────────────

	/**
	 * Finds a row by primary key. Returns SQLRow-format entry, or null if not found.
	 */
	@SuppressWarnings("unchecked")
	public static AVector<ACell> get(ACell block, ABlob pk) {
		if (block instanceof AArrayBlob) {
			AArrayBlob blob = (AArrayBlob) block;
			if (blob.count() < 4) return null;
			byte[] bs = blob.getInternalArray();
			int base = blob.getInternalOffset();
			int n = rInt(bs, base);
			if (n == 0) return null;
			int ds = base + dataStart(n);
			int idx = bsearch(bs, n, base, ds, pk);
			if (idx < 0) return null;
			int off = rInt(bs, base + HDR + 4*idx);
			int pkLen = bs[ds+off] & 0xFF;
			return decodeRow(bs, ds + off + 1 + pkLen);
		}
		if (!(block instanceof AVector)) return null;
		AVector<ACell> v = (AVector<ACell>) block;
		if (!isBlock(v)) return null;
		int n = (int)((CVMLong)v.get(0)).longValue();
		int lo = 0, hi = n - 1;
		while (lo <= hi) {
			int mid = (lo + hi) >>> 1;
			int cmp = ((ABlob)v.get(1+2*mid)).compareTo(pk);
			if (cmp == 0) return (AVector<ACell>) v.get(2+2*mid);
			if (cmp < 0) lo = mid + 1; else hi = mid - 1;
		}
		return null;
	}

	// ── Indexed access ────────────────────────────────────────────────────────

	/** Returns the pk at position i (0-based). */
	@SuppressWarnings("unchecked")
	public static ABlob getKey(ACell block, int i) {
		if (block instanceof AArrayBlob) {
			AArrayBlob blob = (AArrayBlob) block;
			byte[] bs = blob.getInternalArray();
			int base = blob.getInternalOffset();
			int n = rInt(bs, base);
			int ds = base + dataStart(n);
			int off = rInt(bs, base + HDR + 4*i);
			int pkLen = bs[ds+off] & 0xFF;
			byte[] pkB = new byte[pkLen];
			System.arraycopy(bs, ds+off+1, pkB, 0, pkLen);
			return Blob.wrap(pkB);
		}
		AVector<ACell> v = (AVector<ACell>) block;
		return (ABlob) v.get(1 + 2*i);
	}

	/** Returns the row entry at position i (0-based). */
	@SuppressWarnings("unchecked")
	public static AVector<ACell> getRow(ACell block, int i) {
		if (block instanceof AArrayBlob) {
			AArrayBlob blob = (AArrayBlob) block;
			byte[] bs = blob.getInternalArray();
			int base = blob.getInternalOffset();
			int n = rInt(bs, base);
			int ds = base + dataStart(n);
			int off = rInt(bs, base + HDR + 4*i);
			int es = ds + off;
			int pkLen = bs[es] & 0xFF;
			return decodeRow(bs, es + 1 + pkLen);
		}
		AVector<ACell> v = (AVector<ACell>) block;
		return (AVector<ACell>) v.get(2 + 2*i);
	}

	// ── Single-entry mutation ─────────────────────────────────────────────────

	/**
	 * Adds or replaces a row entry in the block, maintaining sorted pk order.
	 * Creates a new single-entry flat block if block is null or not a block.
	 *
	 * @return New block as flat Blob with the row inserted or updated
	 */
	public static ACell put(ACell block, ABlob pk, AVector<ACell> row) {
		if (!isBlock(block)) {
			// null or raw SQLRow — create single-entry flat block
			List<ABlob> pks = new ArrayList<>(1);
			List<AVector<ACell>> rows = new ArrayList<>(1);
			pks.add(pk); rows.add(row);
			return buildFlat(pks, rows);
		}
		List<ABlob> pks = new ArrayList<>(count(block) + 1);
		List<AVector<ACell>> rows = new ArrayList<>(count(block) + 1);
		extractAll(block, pks, rows);
		// binary search for pk
		int lo = 0, hi = pks.size() - 1, pos = pks.size();
		while (lo <= hi) {
			int mid = (lo + hi) >>> 1;
			int cmp = pks.get(mid).compareTo(pk);
			if (cmp == 0) { rows.set(mid, row); return buildFlat(pks, rows); }
			if (cmp < 0) lo = mid + 1; else { pos = mid; hi = mid - 1; }
		}
		pks.add(pos, pk);
		rows.add(pos, row);
		return buildFlat(pks, rows);
	}

	// ── Batch mutation ────────────────────────────────────────────────────────

	/**
	 * Merges a sorted list of new (pk → row) entries into the block.
	 * New entries override existing entries with the same pk.
	 * newPks must be sorted ascending and same size as newRows.
	 *
	 * @param newLiveCount if non-null, newLiveCount[0] is incremented for each newly-live row
	 * @return new block as flat Blob
	 */
	public static ACell putAll(ACell block,
			List<ABlob> newPks, List<AVector<ACell>> newRows,
			int[] newLiveCount) {
		List<ABlob> ePks = new ArrayList<>();
		List<AVector<ACell>> eRows = new ArrayList<>();
		if (isBlock(block)) extractAll(block, ePks, eRows);

		int en = ePks.size(), nn = newPks.size();
		List<ABlob> rPks = new ArrayList<>(en + nn);
		List<AVector<ACell>> rRows = new ArrayList<>(en + nn);

		int ei = 0, ni = 0;
		while (ei < en && ni < nn) {
			int cmp = ePks.get(ei).compareTo(newPks.get(ni));
			if (cmp < 0) {
				rPks.add(ePks.get(ei)); rRows.add(eRows.get(ei)); ei++;
			} else if (cmp > 0) {
				if (newLiveCount != null && SQLRow.isLive(newRows.get(ni))) newLiveCount[0]++;
				rPks.add(newPks.get(ni)); rRows.add(newRows.get(ni)); ni++;
			} else {
				// same pk — new wins; track live transition
				AVector<ACell> existingRow = eRows.get(ei);
				AVector<ACell> newRow = newRows.get(ni);
				if (newLiveCount != null && !SQLRow.isLive(existingRow) && SQLRow.isLive(newRow)) {
					newLiveCount[0]++;
				}
				rPks.add(newPks.get(ni)); rRows.add(newRow); ei++; ni++;
			}
		}
		while (ei < en) { rPks.add(ePks.get(ei)); rRows.add(eRows.get(ei)); ei++; }
		while (ni < nn) {
			if (newLiveCount != null && SQLRow.isLive(newRows.get(ni))) newLiveCount[0]++;
			rPks.add(newPks.get(ni)); rRows.add(newRows.get(ni)); ni++;
		}
		return buildFlat(rPks, rRows);
	}

	// ── Iteration ─────────────────────────────────────────────────────────────

	/**
	 * Iterates all (pk, rowEntry) pairs in pk order.
	 */
	@SuppressWarnings("unchecked")
	public static void forEach(ACell block, BiConsumer<ABlob, AVector<ACell>> action) {
		if (block instanceof AArrayBlob) {
			AArrayBlob blob = (AArrayBlob) block;
			byte[] bs = blob.getInternalArray();
			int base = blob.getInternalOffset();
			int n = rInt(bs, base);
			int ds = base + dataStart(n);
			for (int i = 0; i < n; i++) {
				int off = rInt(bs, base + HDR + 4*i);
				int es = ds + off;
				int pkLen = bs[es] & 0xFF;
				byte[] pkB = new byte[pkLen];
				System.arraycopy(bs, es+1, pkB, 0, pkLen);
				action.accept(Blob.wrap(pkB), decodeRow(bs, es + 1 + pkLen));
			}
			return;
		}
		if (!(block instanceof AVector)) return;
		AVector<ACell> v = (AVector<ACell>) block;
		if (!isBlock(v)) return;
		int n = (int)((CVMLong)v.get(0)).longValue();
		for (int i = 0; i < n; i++) {
			action.accept((ABlob)v.get(1+2*i), (AVector<ACell>)v.get(2+2*i));
		}
	}

	// ── Merge ─────────────────────────────────────────────────────────────────

	/**
	 * Merges two blocks covering the same key prefix using row-level LWW semantics.
	 */
	public static ACell merge(ACell a, ACell b) {
		if (!isBlock(a)) return b;
		if (!isBlock(b)) return a;
		if (a == b) return a;  // identical reference — no work needed

		List<ABlob> aPks = new ArrayList<>(), bPks = new ArrayList<>();
		List<AVector<ACell>> aRows = new ArrayList<>(), bRows = new ArrayList<>();
		extractAll(a, aPks, aRows);
		extractAll(b, bPks, bRows);

		int na = aPks.size(), nb = bPks.size();
		List<ABlob> rPks = new ArrayList<>(na + nb);
		List<AVector<ACell>> rRows = new ArrayList<>(na + nb);

		boolean changed = false;
		int i = 0, j = 0;
		while (i < na && j < nb) {
			int cmp = aPks.get(i).compareTo(bPks.get(j));
			if (cmp < 0) { rPks.add(aPks.get(i)); rRows.add(aRows.get(i++)); }
			else if (cmp > 0) {
				changed = true;
				rPks.add(bPks.get(j)); rRows.add(bRows.get(j++));
			} else {
				AVector<ACell> ar = aRows.get(i), br = bRows.get(j);
				AVector<ACell> merged = SQLRow.merge(ar, br);
				if (merged != ar) changed = true;
				rPks.add(aPks.get(i));
				rRows.add(merged);
				i++; j++;
			}
		}
		while (i < na) { rPks.add(aPks.get(i)); rRows.add(aRows.get(i++)); }
		while (j < nb) { changed = true; rPks.add(bPks.get(j)); rRows.add(bRows.get(j++)); }

		if (!changed) return a;  // a dominates — return without re-encoding
		return buildFlat(rPks, rRows);
	}
}
