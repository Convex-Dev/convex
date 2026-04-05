package convex.core.data.util;

import java.util.ArrayList;
import java.util.List;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Cells;
import convex.core.data.Keyword;
import convex.core.data.MapEntry;
import convex.core.data.Strings;
import convex.core.util.JSON;

/**
 * Budget-bounded JSON5 renderer for CVM {@link ACell} values.
 *
 * CellExplorer produces a text representation of any cell, truncated to fit
 * within a caller-specified storage-size budget (measured in
 * {@link Cells#storageSize} units). It is intended for LLM and tool consumption
 * — progressive exploration of arbitrarily large lattice structures without
 * overwhelming context windows.
 *
 * See {@code convex-core/docs/CELL_EXPLORER_DESIGN.md} for the full design.
 *
 * <h2>Usage</h2>
 * <pre>
 * CellExplorer explorer = new CellExplorer(2048);
 * AString out = explorer.explore(cell);
 * </pre>
 *
 * <h2>Path drill-down</h2>
 *
 * CellExplorer operates on a single resolved cell. Callers needing path-based
 * navigation should resolve the target cell first using the existing
 * {@code convex.lattice.cursor} infrastructure, then pass the resolved cell
 * to {@link #explore(ACell)}.
 *
 * <h2>v1 scope (this commit)</h2>
 * <ul>
 *   <li>Fast path for leaf cells and non-map containers that fit in budget —
 *       delegates to {@link JSON#appendJSON}.</li>
 *   <li>CellExplorer-specific map rendering: unquoted keyword keys per OQ-1,
 *       partial rendering with overflow annotation per OQ-3, fully-truncated
 *       {@code {/* Map, N keys, SZ *}{@code /}} form when budget is too small.</li>
 * </ul>
 *
 * <h2>Not yet implemented</h2>
 * <ul>
 *   <li>Container truncation for vectors, lists, and sets (step 6).</li>
 *   <li>Partial forms for large strings and blobs.</li>
 *   <li>CellExplorer-specific renderings for Sets ({@code /* Set *}{@code /}
 *       marker), Addresses ({@code "#42"}), and Keywords / Symbols as values —
 *       the fast path currently inherits JSON's renderings for these.</li>
 *   <li>Pretty-printing with indentation (compact and pretty produce the
 *       same output for now).</li>
 *   <li>NaN / Infinity special-case for {@code CVMDouble} (OQ-8).</li>
 * </ul>
 *
 * <h2>Known v1 limitation: nested-map-inside-non-map</h2>
 *
 * When a fitting vector or list contains a map, the outer container is rendered
 * via JSON's path which produces JSON's (quoted-key) map form, bypassing the
 * CellExplorer renderer. This resolves in step 6 when sequences get their own
 * renderer that recurses through {@link #explore} for each element.
 */
public class CellExplorer {

	/** Reserve for the {@code {/* Map, N keys, SZ *}{@code /}} annotation. */
	private static final long ANNOTATION_RESERVE = 30;

	/** Per-entry structural cost for maps ({@code ": "}, {@code ", "}, slack). */
	private static final long ENTRY_OVERHEAD = 10;

	/** Minimum budget to render a value meaningfully. */
	private static final long MIN_VALUE_BUDGET = 10;

	/** Budget in {@link Cells#storageSize} units. */
	private final int budget;

	/** Compact mode (no indentation / newlines) when true. */
	private final boolean compact;

	/**
	 * Create a CellExplorer with default (pretty) formatting.
	 *
	 * @param budget Budget in {@link Cells#storageSize} units
	 */
	public CellExplorer(int budget) {
		this(budget, false);
	}

	/**
	 * Create a CellExplorer with explicit compact flag.
	 *
	 * @param budget Budget in {@link Cells#storageSize} units
	 * @param compact true to suppress indentation and newlines
	 */
	public CellExplorer(int budget, boolean compact) {
		this.budget = budget;
		this.compact = compact;
	}

	/**
	 * Render a cell to an AString.
	 *
	 * @param cell Cell to render (may be null to render nil)
	 * @return AString containing the rendering
	 */
	public AString explore(ACell cell) {
		BlobBuilder bb = new BlobBuilder();
		explore(cell, bb, budget, 0);
		return Strings.create(bb.toBlob());
	}

	// ---- Core dispatcher ----

	@SuppressWarnings("unchecked")
	private void explore(ACell cell, BlobBuilder bb, long remaining, int indent) {
		// Maps always go through the custom renderer — JSON's map path quotes
		// all keys, which conflicts with OQ-1's unquoted-keyword form.
		if (cell instanceof AMap) {
			exploreMap((AMap<ACell, ACell>) cell, bb, remaining, indent);
			return;
		}

		long cellSize = Cells.storageSize(cell);
		if (cellSize <= remaining) {
			// Fast path: delegate to JSON.appendJSON for everything non-map.
			// NOTE: Sets, addresses, keywords-as-values, symbols, and
			// non-finite doubles still inherit JSON's divergent rendering —
			// corrected in later commits.
			JSON.appendJSON(bb, cell);
			return;
		}

		// Non-map cell too large — placeholder until step 6.
		bb.append("/* TRUNCATED: storageSize=");
		bb.append(Long.toString(cellSize));
		bb.append(" > budget=");
		bb.append(Long.toString(remaining));
		bb.append(" (non-map truncation not yet implemented) */");
	}

	// ---- Map rendering ----

	private void exploreMap(AMap<ACell, ACell> map, BlobBuilder bb, long remaining, int indent) {
		long count = map.count();
		if (count == 0) {
			bb.append("{}");
			return;
		}

		long mapSize = Cells.storageSize(map);

		// Fast path: whole map fits — render fully with OQ-1 key formatting.
		if (mapSize <= remaining) {
			renderMapFull(map, bb, remaining, indent);
			return;
		}

		// Budget too small even for the annotation — fully-truncated form.
		if (ANNOTATION_RESERVE >= remaining) {
			appendFullyTruncatedMap(bb, count, mapSize);
			return;
		}

		// Partial rendering with key-first scan.
		long contentBudget = remaining - ANNOTATION_RESERVE;
		renderMapTruncated(map, bb, contentBudget, mapSize, indent);
	}

	private void renderMapFull(AMap<ACell, ACell> map, BlobBuilder bb, long remaining, int indent) {
		long n = map.count();
		bb.append('{');
		for (long i = 0; i < n; i++) {
			if (i > 0) bb.append(", ");
			MapEntry<ACell, ACell> e = map.entryAt(i);
			renderKey(bb, e.getKey());
			bb.append(": ");
			// Whole map fits remaining, so each value trivially fits too.
			explore(e.getValue(), bb, remaining, indent + 1);
		}
		bb.append('}');
	}

	private void renderMapTruncated(AMap<ACell, ACell> map, BlobBuilder bb, long contentBudget, long mapSize, int indent) {
		long totalEntries = map.count();
		long keyCost = 0;
		List<MapEntry<ACell, ACell>> visible = new ArrayList<>();

		// Phase 1: scan keys to decide how many entries we can show.
		for (long i = 0; i < totalEntries; i++) {
			MapEntry<ACell, ACell> e = map.entryAt(i);
			long kCost = Cells.storageSize(e.getKey()) + ENTRY_OVERHEAD;
			if (keyCost + kCost + MIN_VALUE_BUDGET > contentBudget) break;
			visible.add(e);
			keyCost += kCost;
		}

		// If we can't show any entries, fall back to fully-truncated form
		// (cleaner than an empty partial form with only an overflow comment).
		if (visible.isEmpty()) {
			appendFullyTruncatedMap(bb, totalEntries, mapSize);
			return;
		}

		long overflow = totalEntries - visible.size();
		long valueBudget = Math.max(MIN_VALUE_BUDGET, (contentBudget - keyCost) / visible.size());

		// Phase 2: render visible entries.
		bb.append('{');
		for (int i = 0; i < visible.size(); i++) {
			if (i > 0) bb.append(", ");
			MapEntry<ACell, ACell> e = visible.get(i);
			renderKey(bb, e.getKey());
			bb.append(": ");
			explore(e.getValue(), bb, valueBudget, indent + 1);
		}

		if (overflow > 0) {
			bb.append(", /* +");
			bb.append(Long.toString(overflow));
			bb.append(" more, ");
			appendSizeAnnotation(bb, mapSize);
			bb.append(" */");
		}

		bb.append('}');
	}

	// ---- Key formatting (OQ-1) ----

	/**
	 * Render a map key to the output buffer. Keywords and strings become
	 * unquoted JSON5 identifiers when their name matches
	 * {@code [a-zA-Z_$][a-zA-Z0-9_$]*}; otherwise they are quoted and escaped.
	 * All other key types ({@code Integer}, {@code Address}, {@code Blob}, ...)
	 * go through {@link JSON#jsonKey} for string coercion and are always
	 * quoted (JSON5 forbids bare numeric literals as object keys).
	 */
	private static void renderKey(BlobBuilder bb, ACell key) {
		if (key instanceof Keyword) {
			String name = ((Keyword) key).getName().toString();
			appendIdentifierOrQuoted(bb, name);
			return;
		}
		if (key instanceof AString) {
			String str = ((AString) key).toString();
			appendIdentifierOrQuoted(bb, str);
			return;
		}
		// Integer, Address, Blob, Symbol (unusual as key), etc. — always quoted.
		String coerced = JSON.jsonKey(key);
		bb.append('"');
		JSON.appendCVMStringQuoted(bb, coerced);
		bb.append('"');
	}

	private static void appendIdentifierOrQuoted(BlobBuilder bb, String name) {
		if (isValidIdentifier(name)) {
			bb.append(name);
		} else {
			bb.append('"');
			JSON.appendCVMStringQuoted(bb, name);
			bb.append('"');
		}
	}

	private static boolean isValidIdentifier(String s) {
		if (s == null || s.isEmpty()) return false;
		char c = s.charAt(0);
		if (!((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c == '_' || c == '$')) return false;
		for (int i = 1; i < s.length(); i++) {
			c = s.charAt(i);
			if (!((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_' || c == '$')) return false;
		}
		return true;
	}

	// ---- Annotations ----

	private static void appendFullyTruncatedMap(BlobBuilder bb, long count, long mapSize) {
		bb.append("{/* Map, ");
		bb.append(Long.toString(count));
		bb.append(count == 1 ? " key, " : " keys, ");
		appendSizeAnnotation(bb, mapSize);
		bb.append(" */}");
	}

	/**
	 * Appends a human-readable size annotation per the design doc:
	 * {@code <1024 → {n}B}; {@code <1MB → {n.1f}KB}; {@code <1GB → {n.1f}MB};
	 * else {@code {n.1f}GB}.
	 */
	private static void appendSizeAnnotation(BlobBuilder bb, long bytes) {
		if (bytes < 1024L) {
			bb.append(Long.toString(bytes));
			bb.append('B');
		} else if (bytes < 1024L * 1024) {
			bb.append(String.format("%.1fKB", bytes / 1024.0));
		} else if (bytes < 1024L * 1024 * 1024) {
			bb.append(String.format("%.1fMB", bytes / (1024.0 * 1024)));
		} else {
			bb.append(String.format("%.1fGB", bytes / (1024.0 * 1024 * 1024)));
		}
	}
}
