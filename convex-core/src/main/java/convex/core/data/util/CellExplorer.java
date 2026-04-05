package convex.core.data.util;

import java.util.ArrayList;
import java.util.List;

import convex.core.data.ACell;
import convex.core.data.ACollection;
import convex.core.data.AList;
import convex.core.data.AMap;
import convex.core.data.ASet;
import convex.core.data.AString;
import convex.core.data.AVector;
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
 * <h2>Rendering coverage</h2>
 * <ul>
 *   <li>Maps: CellExplorer-specific renderer with unquoted keyword keys (OQ-1),
 *       partial rendering with merged overflow+size annotation (OQ-3), and
 *       fully-truncated form.</li>
 *   <li>Vectors, Lists, Sets: CellExplorer-specific renderer with a running-
 *       remainder budget (design doc §Sequence Rendering), inline
 *       {@code /* Set *}{@code /} marker for sets, and fully-truncated form.</li>
 *   <li>Leaf cells: fast path delegates to {@link JSON#appendJSON} when the
 *       cell fits budget.</li>
 * </ul>
 *
 * <h2>Not yet implemented</h2>
 * <ul>
 *   <li>Partial forms for large strings and blobs.</li>
 *   <li>CellExplorer-specific renderings for Addresses ({@code "#42"}),
 *       Keywords / Symbols as values, and non-finite {@code CVMDouble} (OQ-8)
 *       — the fast path currently inherits JSON's renderings for these leaf
 *       types.</li>
 *   <li>Pretty-printing with indentation (compact and pretty produce the same
 *       output for now).</li>
 * </ul>
 */
public class CellExplorer {

	/** Reserve for the {@code {/* Map, N keys, SZ *}{@code /}}-style annotation. */
	private static final long ANNOTATION_RESERVE = 30;

	/** Per-entry structural cost for maps ({@code ": "}, {@code ", "}, slack). */
	private static final long ENTRY_OVERHEAD = 10;

	/** Minimum budget to render a map value meaningfully. */
	private static final long MIN_VALUE_BUDGET = 10;

	/** Minimum budget to render a sequence item meaningfully. */
	private static final long MIN_ITEM_BUDGET = 10;

	/** Kind marker for sequence-shaped collections. Controls label + inline marker. */
	private enum CollectionKind {
		VECTOR("Vec", false),
		LIST("List", false),
		SET("Set", true);

		final String label;
		/** true if this kind needs an inline {@code /* Set *}{@code /} marker when rendered fully. */
		final boolean needsMarker;

		CollectionKind(String label, boolean needsMarker) {
			this.label = label;
			this.needsMarker = needsMarker;
		}
	}

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
		// Maps use the custom renderer (OQ-1 unquoted keyword keys).
		if (cell instanceof AMap) {
			exploreMap((AMap<ACell, ACell>) cell, bb, remaining, indent);
			return;
		}
		// Vectors, lists, sets use the custom sequence renderer.
		if (cell instanceof AVector) {
			exploreCollection((ACollection<ACell>) cell, bb, remaining, indent, CollectionKind.VECTOR);
			return;
		}
		if (cell instanceof AList) {
			exploreCollection((ACollection<ACell>) cell, bb, remaining, indent, CollectionKind.LIST);
			return;
		}
		if (cell instanceof ASet) {
			exploreCollection((ACollection<ACell>) cell, bb, remaining, indent, CollectionKind.SET);
			return;
		}

		// Leaf cells: fast path via JSON.appendJSON when fitting.
		long cellSize = Cells.storageSize(cell);
		if (cellSize <= remaining) {
			// NOTE: Addresses, keywords-as-values, symbols, and non-finite
			// doubles still inherit JSON's divergent rendering. Resolved in
			// a later commit (leaf type overrides + OQ-8).
			JSON.appendJSON(bb, cell);
			return;
		}

		// Leaf too large — partial forms (strings, blobs) land in a later
		// commit. For now, emit an annotation-only form.
		bb.append("/* truncated leaf, storageSize=");
		bb.append(Long.toString(cellSize));
		bb.append(" > budget=");
		bb.append(Long.toString(remaining));
		bb.append(" */");
	}

	// ---- Map rendering ----

	private void exploreMap(AMap<ACell, ACell> map, BlobBuilder bb, long remaining, int indent) {
		long count = map.count();
		if (count == 0) {
			bb.append("{}");
			return;
		}

		long mapSize = Cells.storageSize(map);

		if (mapSize <= remaining) {
			renderMapFull(map, bb, remaining, indent);
			return;
		}

		if (ANNOTATION_RESERVE >= remaining) {
			appendFullyTruncatedMap(bb, count, mapSize);
			return;
		}

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
			explore(e.getValue(), bb, remaining, indent + 1);
		}
		bb.append('}');
	}

	private void renderMapTruncated(AMap<ACell, ACell> map, BlobBuilder bb, long contentBudget, long mapSize, int indent) {
		long totalEntries = map.count();
		long keyCost = 0;
		List<MapEntry<ACell, ACell>> visible = new ArrayList<>();

		for (long i = 0; i < totalEntries; i++) {
			MapEntry<ACell, ACell> e = map.entryAt(i);
			long kCost = Cells.storageSize(e.getKey()) + ENTRY_OVERHEAD;
			if (keyCost + kCost + MIN_VALUE_BUDGET > contentBudget) break;
			visible.add(e);
			keyCost += kCost;
		}

		if (visible.isEmpty()) {
			appendFullyTruncatedMap(bb, totalEntries, mapSize);
			return;
		}

		long overflow = totalEntries - visible.size();
		long valueBudget = Math.max(MIN_VALUE_BUDGET, (contentBudget - keyCost) / visible.size());

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

	// ---- Sequence / Set rendering ----

	private void exploreCollection(ACollection<ACell> coll, BlobBuilder bb, long remaining, int indent, CollectionKind kind) {
		long count = coll.count();
		if (count == 0) {
			bb.append(kind.needsMarker ? "[/* Set */]" : "[]");
			return;
		}

		long size = Cells.storageSize(coll);

		if (size <= remaining) {
			renderCollectionFull(coll, bb, remaining, indent, kind);
			return;
		}

		if (ANNOTATION_RESERVE >= remaining) {
			appendFullyTruncatedCollection(bb, count, size, kind);
			return;
		}

		long contentBudget = remaining - ANNOTATION_RESERVE;
		renderCollectionTruncated(coll, bb, contentBudget, size, indent, kind);
	}

	private void renderCollectionFull(ACollection<ACell> coll, BlobBuilder bb, long remaining, int indent, CollectionKind kind) {
		bb.append('[');
		boolean first = true;
		for (ACell item : coll) {
			if (!first) bb.append(", ");
			explore(item, bb, remaining, indent + 1);
			first = false;
		}
		if (kind.needsMarker) {
			bb.append(" /* Set */");
		}
		bb.append(']');
	}

	private void renderCollectionTruncated(ACollection<ACell> coll, BlobBuilder bb, long contentBudget, long collSize, int indent, CollectionKind kind) {
		long totalItems = coll.count();
		long consumed = 0;
		long shown = 0;

		bb.append('[');
		for (ACell item : coll) {
			long itemRemaining = contentBudget - consumed;
			if (itemRemaining < MIN_ITEM_BUDGET) break;
			if (shown > 0) bb.append(", ");
			long itemCost = Math.min(Cells.storageSize(item), itemRemaining);
			explore(item, bb, itemRemaining, indent + 1);
			consumed += itemCost;
			shown++;
		}

		long overflow = totalItems - shown;
		if (overflow > 0) {
			if (shown > 0) bb.append(", ");
			bb.append("/* +");
			bb.append(Long.toString(overflow));
			bb.append(" more, ");
			if (kind.needsMarker) {
				bb.append("Set, ");
			}
			appendSizeAnnotation(bb, collSize);
			bb.append(" */");
		} else if (kind.needsMarker) {
			// Rare: all items visible despite truncated path (overhead undercounted).
			// Still need the inline /* Set */ marker.
			bb.append(" /* Set */");
		}

		bb.append(']');
	}

	// ---- Key formatting (OQ-1) ----

	/**
	 * Render a map key. Keywords and strings become unquoted JSON5 identifiers
	 * when their name matches {@code [a-zA-Z_$][a-zA-Z0-9_$]*}; otherwise
	 * quoted and escaped. All other types ({@code Integer}, {@code Address},
	 * {@code Blob}, ...) go through {@link JSON#jsonKey} and are always
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

	private static void appendFullyTruncatedCollection(BlobBuilder bb, long count, long size, CollectionKind kind) {
		bb.append("[/* ");
		bb.append(kind.label);
		bb.append(", ");
		bb.append(Long.toString(count));
		bb.append(count == 1 ? " item, " : " items, ");
		appendSizeAnnotation(bb, size);
		bb.append(" */]");
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
