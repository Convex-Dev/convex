package convex.core.data.util;

import java.util.ArrayList;
import java.util.List;

import convex.core.cvm.Address;
import convex.core.data.ABlob;
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
import convex.core.data.Symbol;
import convex.core.data.prim.CVMDouble;
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
 * <h2>Map entry order</h2>
 *
 * Map entries are rendered in the order returned by the underlying
 * {@link AMap#entryAt(long)} — for {@code AHashMap} this is hash order, not
 * insertion order. LLM consumers should treat ordering as non-semantic.
 *
 * <h2>Rendering coverage</h2>
 * <ul>
 *   <li>Maps: CellExplorer-specific renderer with unquoted keyword keys (OQ-1),
 *       partial rendering with merged overflow+size annotation (OQ-3), and
 *       fully-truncated form.</li>
 *   <li>Vectors, Lists, Sets: CellExplorer-specific renderer with a running-
 *       remainder budget (design doc §Sequence Rendering), inline
 *       {@code /* Set *}{@code /} marker for sets, and fully-truncated form.</li>
 *   <li>Leaf cells: CellExplorer-specific overrides for {@link Address}
 *       ({@code "#42"}), {@link Keyword}/{@link Symbol} as values
 *       ({@code ":name"} / {@code "'name"}), and non-finite {@link CVMDouble}
 *       (bare {@code NaN} / {@code Infinity} / {@code -Infinity}, OQ-8).
 *       Other leaves delegate to {@link JSON#appendJSON} on the fast path.</li>
 *   <li>String and blob partial forms: when a leaf exceeds budget, strings
 *       render as {@code "prefix..." /* String, NKB *}{@code /} and blobs as
 *       {@code "0xBYTES..." /* Blob, NKB *}{@code /}; smaller than that still,
 *       any leaf falls back to a bare {@code null} with a truncation comment.</li>
 * </ul>
 *
 * <h2>Not yet implemented</h2>
 * <ul>
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

	/** Reserve for leaf partial-form annotations ({@code " /* String, 99.9GB *}{@code /"}). */
	private static final long LEAF_ANNOTATION_RESERVE = 30;

	/** Minimum content budget before we fall back to annotation-only leaf truncation. */
	private static final long MIN_LEAF_CONTENT_BUDGET = 6;

	/** Number of spaces per indent level in pretty mode. */
	private static final int INDENT_WIDTH = 2;

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
	 * Create a CellExplorer with default (compact) formatting — single-line
	 * output, optimised for token efficiency when feeding LLMs.
	 *
	 * @param budget Budget in {@link Cells#storageSize} units
	 */
	public CellExplorer(int budget) {
		this(budget, true);
	}

	/**
	 * Create a CellExplorer with explicit compact flag.
	 *
	 * @param budget Budget in {@link Cells#storageSize} units
	 * @param compact {@code true} for single-line output; {@code false} for
	 *                pretty-printed output with indentation and newlines
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

		// Leaf cells: type-aware rendering with budget-aware partial forms.
		exploreLeaf(cell, bb, remaining);
	}

	// ---- Leaf rendering ----

	private void exploreLeaf(ACell cell, BlobBuilder bb, long remaining) {
		long cellSize = Cells.storageSize(cell);
		if (cellSize <= remaining) {
			renderLeafFull(cell, bb);
			return;
		}

		// Over budget: attempt a partial form for strings and blobs, else
		// fall through to an annotation-only placeholder that is still valid
		// JSON5 (bare `null` + trailing comment).
		if (cell instanceof AString) {
			renderStringPartial((AString) cell, bb, remaining, cellSize);
			return;
		}
		if (cell instanceof ABlob) {
			renderBlobPartial((ABlob) cell, bb, remaining, cellSize);
			return;
		}
		appendLeafTruncatedAnnotation(bb, cell, cellSize);
	}

	/**
	 * Render a leaf cell that fits the budget. CellExplorer overrides
	 * {@link JSON#appendJSON}'s divergent renderings for:
	 * <ul>
	 *   <li>{@link Address} — rendered as {@code "#42"} (quoted) so the
	 *       round-tripped JSON5 preserves the distinction from plain longs.</li>
	 *   <li>{@link Keyword} as value — rendered as {@code ":name"} (quoted).</li>
	 *   <li>{@link Symbol} as value — rendered as {@code "'name"} (quoted).</li>
	 *   <li>Non-finite {@link CVMDouble} — bare {@code NaN},
	 *       {@code Infinity}, or {@code -Infinity} (all JSON5-valid).</li>
	 * </ul>
	 * Any other leaf type falls through to {@link JSON#appendJSON}.
	 */
	private static void renderLeafFull(ACell cell, BlobBuilder bb) {
		if (cell instanceof Address) {
			bb.append('"');
			bb.append('#');
			bb.append(Long.toString(((Address) cell).longValue()));
			bb.append('"');
			return;
		}
		if (cell instanceof Keyword) {
			bb.append('"');
			bb.append(':');
			JSON.appendCVMStringQuoted(bb, ((Keyword) cell).getName().toString());
			bb.append('"');
			return;
		}
		if (cell instanceof Symbol) {
			bb.append('"');
			bb.append('\'');
			JSON.appendCVMStringQuoted(bb, ((Symbol) cell).getName().toString());
			bb.append('"');
			return;
		}
		if (cell instanceof CVMDouble) {
			double v = ((CVMDouble) cell).doubleValue();
			if (Double.isNaN(v)) {
				bb.append("NaN");
				return;
			}
			if (v == Double.POSITIVE_INFINITY) {
				bb.append("Infinity");
				return;
			}
			if (v == Double.NEGATIVE_INFINITY) {
				bb.append("-Infinity");
				return;
			}
			// Finite: fall through to JSON delegation for canonical number form.
		}
		JSON.appendJSON(bb, cell);
	}

	/**
	 * Render an over-budget {@link AString} as a truncated prefix plus
	 * annotation, e.g. {@code "hello wor..." /* String, 4.2KB *}{@code /}.
	 * Never splits a UTF-16 surrogate pair. Uses {@link JSON#appendCVMStringQuoted}
	 * for consistent escaping of quotes, backslashes, and control characters.
	 */
	private static void renderStringPartial(AString s, BlobBuilder bb, long remaining, long sSize) {
		long contentBudget = remaining - LEAF_ANNOTATION_RESERVE - 2; // reserve for surrounding quotes
		if (contentBudget < MIN_LEAF_CONTENT_BUDGET) {
			appendLeafTruncatedAnnotation(bb, s, sSize);
			return;
		}
		String java = s.toString();
		int n = java.length();
		int charCap = (int) Math.min((long) n, contentBudget);
		// Back off one position if the cut point would split a surrogate pair.
		if (charCap > 0 && charCap < n && Character.isHighSurrogate(java.charAt(charCap - 1))) {
			charCap--;
		}
		bb.append('"');
		JSON.appendCVMStringQuoted(bb, java.substring(0, charCap));
		bb.append('"');
		bb.append(" /* String, ");
		appendSizeAnnotation(bb, sSize);
		bb.append(" */");
	}

	/**
	 * Render an over-budget {@link ABlob} as a hex prefix plus annotation,
	 * e.g. {@code "0x4865..." /* Blob, 12.0KB *}{@code /}. Always emits
	 * {@code "0x"} prefix + an even number of hex characters so the prefix is
	 * a valid blob literal (modulo the trailing ellipsis inside the string).
	 */
	private static void renderBlobPartial(ABlob b, BlobBuilder bb, long remaining, long bSize) {
		// Reserve: 2 (quotes) + 2 ("0x") + 3 ("...") = 7, plus annotation reserve.
		long contentBudget = remaining - LEAF_ANNOTATION_RESERVE - 7;
		if (contentBudget < 2) {
			appendLeafTruncatedAnnotation(bb, b, bSize);
			return;
		}
		long bytesAvail = contentBudget / 2;
		long bytesToShow = Math.min(b.count(), bytesAvail);
		bb.append('"');
		bb.append('0');
		bb.append('x');
		for (long i = 0; i < bytesToShow; i++) {
			bb.appendHexByte(b.byteAt(i));
		}
		if (bytesToShow < b.count()) {
			bb.append("...");
		}
		bb.append('"');
		bb.append(" /* Blob, ");
		appendSizeAnnotation(bb, bSize);
		bb.append(" */");
	}

	/**
	 * Fallback truncated-leaf form. Emits a bare {@code null} followed by a
	 * descriptive comment — together valid JSON5 that reloads as nil (a
	 * deliberate, documented lossy fallback for extreme budgets).
	 */
	private static void appendLeafTruncatedAnnotation(BlobBuilder bb, ACell cell, long cellSize) {
		bb.append("null /* truncated ");
		bb.append(leafTypeLabel(cell));
		bb.append(", ");
		appendSizeAnnotation(bb, cellSize);
		bb.append(" */");
	}

	/** Short type label for leaf truncation annotations. */
	private static String leafTypeLabel(ACell cell) {
		if (cell == null) return "nil";
		if (cell instanceof AString) return "String";
		if (cell instanceof ABlob) return "Blob";
		if (cell instanceof Keyword) return "Keyword";
		if (cell instanceof Symbol) return "Symbol";
		if (cell instanceof Address) return "Address";
		if (cell instanceof CVMDouble) return "Double";
		return "leaf";
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
			appendEntrySeparator(bb, i, indent + 1);
			MapEntry<ACell, ACell> e = map.entryAt(i);
			renderKey(bb, e.getKey());
			bb.append(": ");
			explore(e.getValue(), bb, remaining, indent + 1);
		}
		appendClose(bb, indent, n > 0);
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
			appendEntrySeparator(bb, i, indent + 1);
			MapEntry<ACell, ACell> e = visible.get(i);
			renderKey(bb, e.getKey());
			bb.append(": ");
			explore(e.getValue(), bb, valueBudget, indent + 1);
		}

		if (overflow > 0) {
			appendEntrySeparator(bb, visible.size(), indent + 1);
			bb.append("/* +");
			bb.append(Long.toString(overflow));
			bb.append(" more, ");
			appendSizeAnnotation(bb, mapSize);
			bb.append(" */");
		}

		appendClose(bb, indent, !visible.isEmpty());
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
		long n = coll.count();
		long i = 0;
		for (ACell item : coll) {
			appendEntrySeparator(bb, i, indent + 1);
			explore(item, bb, remaining, indent + 1);
			i++;
		}
		if (kind.needsMarker) {
			bb.append(" /* Set */");
		}
		appendClose(bb, indent, n > 0);
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
			appendEntrySeparator(bb, shown, indent + 1);
			long itemCost = Math.min(Cells.storageSize(item), itemRemaining);
			explore(item, bb, itemRemaining, indent + 1);
			consumed += itemCost;
			shown++;
		}

		long overflow = totalItems - shown;
		if (overflow > 0) {
			appendEntrySeparator(bb, shown, indent + 1);
			// Set overflow leads with the type tag so the LLM sees it first:
			// {@code /* Set: +N more, SIZE *}{@code /}.
			bb.append(kind.needsMarker ? "/* Set: +" : "/* +");
			bb.append(Long.toString(overflow));
			bb.append(" more, ");
			appendSizeAnnotation(bb, collSize);
			bb.append(" */");
		} else if (kind.needsMarker) {
			// Rare: all items visible despite truncated path (overhead undercounted).
			// Still need the inline /* Set */ marker.
			bb.append(" /* Set */");
		}

		appendClose(bb, indent, shown > 0 || overflow > 0);
		bb.append(']');
	}

	// ---- Pretty-print helpers ----

	/**
	 * Emit the separator before an entry at the given index. For index 0
	 * there is no comma; from 1 onward a comma is prepended. In compact mode
	 * entries are separated by {@code ", "}; in pretty mode each entry sits
	 * on its own line at the given indent level.
	 */
	private void appendEntrySeparator(BlobBuilder bb, long entryIndex, int indent) {
		if (entryIndex > 0) bb.append(',');
		if (compact) {
			if (entryIndex > 0) bb.append(' ');
		} else {
			appendNewlineIndent(bb, indent);
		}
	}

	/**
	 * Emit the close separator: either nothing (compact) or a newline and
	 * indent to sit the closing bracket on its own line (pretty). Only emits
	 * the newline when the container is non-empty — empty containers stay
	 * tight ({@code {}}, {@code []}).
	 */
	private void appendClose(BlobBuilder bb, int indent, boolean nonEmpty) {
		if (!compact && nonEmpty) {
			appendNewlineIndent(bb, indent);
		}
	}

	/** Append a newline followed by indentation to the given depth level. */
	private static void appendNewlineIndent(BlobBuilder bb, int indent) {
		bb.append('\n');
		int spaces = indent * INDENT_WIDTH;
		for (int i = 0; i < spaces; i++) bb.append(' ');
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
