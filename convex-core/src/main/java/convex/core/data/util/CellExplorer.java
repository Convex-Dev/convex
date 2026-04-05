package convex.core.data.util;

import convex.core.data.ACell;
import convex.core.data.AString;
import convex.core.data.Cells;
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
 *   <li>Fast path: when {@code Cells.storageSize(cell) <= budget} the whole
 *       subtree is rendered by delegating to {@link JSON#appendJSON}.</li>
 * </ul>
 *
 * <h2>Not yet implemented</h2>
 * <ul>
 *   <li>Container truncation (partial map / vector / list / set rendering when
 *       the subtree exceeds budget).</li>
 *   <li>Partial forms for large strings and blobs.</li>
 *   <li>CellExplorer-specific renderings for Keywords, Symbols, Addresses,
 *       and Sets — the fast path currently inherits JSON's renderings, which
 *       differ from the final spec.</li>
 *   <li>Pretty-printing with indentation (compact and pretty both currently
 *       produce JSON's single-line output).</li>
 *   <li>NaN / Infinity special-case for {@code CVMDouble} (OQ-8).</li>
 * </ul>
 */
public class CellExplorer {

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

	// ---- Internals ----

	/**
	 * Core recursive dispatcher. Reads {@link #compact} and other config from
	 * instance fields; only the mutable per-call state (output buffer, remaining
	 * budget, current indent) flows as parameters.
	 *
	 * @param cell Cell to render (may be null)
	 * @param bb Output buffer
	 * @param remaining Remaining budget in storageSize units
	 * @param indent Current indent level (reserved for future pretty-printing)
	 */
	private void explore(ACell cell, BlobBuilder bb, long remaining, int indent) {
		long cellSize = Cells.storageSize(cell);
		if (cellSize <= remaining) {
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
		bb.append(Long.toString(remaining));
		bb.append(" (truncation not yet implemented) */");
	}
}
