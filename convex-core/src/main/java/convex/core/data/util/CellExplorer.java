package convex.core.data.util;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.ASequence;
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
 * <h2>v1 scope (this commit)</h2>
 * <ul>
 *   <li>Public API shape: four {@code explore} overloads.</li>
 *   <li>Fast path: when {@code Cells.storageSize(cell) <= budget} the whole
 *       subtree is rendered by delegating to {@link JSON#appendJSON}.</li>
 *   <li>Path drill-down: keyword-first map lookup, numeric sequence index.</li>
 * </ul>
 *
 * <h2>Not yet implemented</h2>
 * <ul>
 *   <li>Container truncation (partial map / vector / list / set rendering when
 *       the subtree exceeds budget).</li>
 *   <li>Partial forms for large strings and blobs.</li>
 *   <li>CellExplorer-specific renderings for Keywords, Symbols, Addresses, and
 *       Sets — the fast path currently inherits JSON's renderings, which differ
 *       from the final spec.</li>
 *   <li>Pretty-printing with indentation (compact and pretty both currently
 *       produce JSON's single-line output).</li>
 *   <li>NaN / Infinity special-case for {@code CVMDouble} (OQ-8).</li>
 * </ul>
 */
public class CellExplorer {

	/** Static utility class — no instances. */
	private CellExplorer() {}

	/**
	 * Explore a cell with default (pretty-print) output.
	 *
	 * @param cell Cell to render; may be {@code null} to render nil
	 * @param budget Budget in {@link Cells#storageSize} units
	 * @return AString containing the rendering
	 */
	public static AString explore(ACell cell, int budget) {
		return explore(cell, budget, false);
	}

	/**
	 * Explore a cell, optionally in compact mode.
	 *
	 * @param cell Cell to render; may be {@code null} to render nil
	 * @param budget Budget in {@link Cells#storageSize} units
	 * @param compact {@code true} to suppress indentation and newlines
	 * @return AString containing the rendering
	 */
	public static AString explore(ACell cell, int budget, boolean compact) {
		BlobBuilder bb = new BlobBuilder();
		explore(cell, bb, budget, 0, compact);
		return Strings.create(bb.toBlob());
	}

	/**
	 * Navigate to a path within a cell, then render the resolved cell.
	 *
	 * @param cell Root cell
	 * @param path {@code /}-separated path, or {@code ""} / {@code "/"} for root
	 * @param budget Budget in {@link Cells#storageSize} units
	 * @return AString rendering of resolved cell, or path-not-found annotation
	 */
	public static AString explore(ACell cell, String path, int budget) {
		return explore(cell, path, budget, false);
	}

	/**
	 * Navigate to a path, then render with compact or pretty output.
	 *
	 * @param cell Root cell
	 * @param path {@code /}-separated path, or {@code ""} / {@code "/"} for root
	 * @param budget Budget in {@link Cells#storageSize} units
	 * @param compact {@code true} to suppress indentation and newlines
	 * @return AString containing the rendering or path-not-found annotation
	 */
	public static AString explore(ACell cell, String path, int budget, boolean compact) {
		BlobBuilder bb = new BlobBuilder();
		try {
			ACell resolved = navigatePath(cell, path);
			explore(resolved, bb, budget, 0, compact);
		} catch (PathNotFoundException e) {
			bb.append("/* path not found: ");
			bb.append(e.path);
			bb.append(" */");
		}
		return Strings.create(bb.toBlob());
	}

	// ---- Internals ----

	/**
	 * Core recursive dispatcher. All rendering goes through here.
	 *
	 * @param cell Cell to render (may be null)
	 * @param bb Output buffer
	 * @param budget Remaining budget in storageSize units
	 * @param indent Current indent level (reserved for future pretty-printing)
	 * @param compact Compact vs pretty mode (reserved for future pretty-printing)
	 */
	private static void explore(ACell cell, BlobBuilder bb, long budget, int indent, boolean compact) {
		long cellSize = Cells.storageSize(cell);
		if (cellSize <= budget) {
			// Fast path: whole subtree fits. Delegate to JSON.appendJSON.
			// NOTE: JSON's renderings for Sets, Addresses, Keywords, Symbols
			// differ from the final CellExplorer spec — those adjustments land
			// in a later commit alongside container truncation.
			JSON.appendJSON(bb, cell);
			return;
		}
		// Truncated path — implemented in subsequent commits (container
		// truncation, leaf truncation, doubles special-case).
		bb.append("/* TRUNCATED: storageSize=");
		bb.append(Long.toString(cellSize));
		bb.append(" > budget=");
		bb.append(Long.toString(budget));
		bb.append(" (truncation not yet implemented) */");
	}

	/** Sentinel exception for path navigation failure. */
	private static class PathNotFoundException extends RuntimeException {
		private static final long serialVersionUID = 1L;
		final String path;
		PathNotFoundException(String path) { this.path = path; }
	}

	/**
	 * Navigate a {@code /}-separated path starting from {@code root}. Empty
	 * string or {@code "/"} returns the root unchanged.
	 *
	 * Map segments are looked up keyword-first then string (OQ-9); sequence
	 * segments must be numeric indices.
	 *
	 * @throws PathNotFoundException if any segment fails to resolve
	 */
	private static ACell navigatePath(ACell root, String path) {
		if (path == null || path.isEmpty() || path.equals("/")) {
			return root;
		}
		String stripped = path.startsWith("/") ? path.substring(1) : path;
		if (stripped.isEmpty()) return root;
		String[] segments = stripped.split("/");
		ACell current = root;
		for (String segment : segments) {
			current = resolveSegment(current, segment, path);
		}
		return current;
	}

	@SuppressWarnings("unchecked")
	private static ACell resolveSegment(ACell container, String segment, String fullPath) {
		if (container instanceof AMap) {
			AMap<ACell, ACell> m = (AMap<ACell, ACell>) container;
			// Try Keyword first (OQ-9).
			Keyword kw = Keyword.create(segment);
			if (kw != null) {
				MapEntry<ACell, ACell> e = m.getEntry(kw);
				if (e != null) return e.getValue();
			}
			// Fall back to AString.
			AString key = Strings.create(segment);
			MapEntry<ACell, ACell> e = m.getEntry(key);
			if (e != null) return e.getValue();
			throw new PathNotFoundException(fullPath);
		}
		if (container instanceof ASequence) {
			ASequence<ACell> s = (ASequence<ACell>) container;
			long idx;
			try {
				idx = Long.parseLong(segment);
			} catch (NumberFormatException e) {
				throw new PathNotFoundException(fullPath);
			}
			if (idx < 0 || idx >= s.count()) {
				throw new PathNotFoundException(fullPath);
			}
			return s.get(idx);
		}
		// Path goes deeper than a leaf, or container type has no path semantics.
		throw new PathNotFoundException(fullPath);
	}
}
