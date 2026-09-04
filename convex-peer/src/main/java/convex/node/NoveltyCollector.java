package convex.node;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.function.Consumer;

import convex.core.data.ACell;
import convex.core.data.Format;
import convex.core.data.Ref;

/**
 * Bounded novelty callback for lattice propagation, retaining the most recently
 * announced non-embedded cells. Store announcement still visits and records every
 * cell; only the eager propagation working set is truncated. Keeping the tail favours
 * cells nearest the announced root, while omitted branches remain available by hash.
 *
 * <p>What to carry eagerly is a propagator's decision, so this lives with the lattice
 * propagator rather than in the data layer. Consensus updates use
 * {@code convex.peer.UpdateAccumulator}, which is bounded by bytes only.</p>
 */
final class NoveltyCollector implements Consumer<Ref<ACell>> {
	/** Conservative cap on references retained while collecting propagation novelty. */
	static final int MAX_NOVELTY_CELLS = 65_536;

	private final long byteLimit;
	private final int cellLimit;
	private final ArrayDeque<ACell> cells=new ArrayDeque<>();
	private long estimatedBytes;
	private long omitted;

	NoveltyCollector(long byteLimit) {
		this(byteLimit,MAX_NOVELTY_CELLS);
	}

	NoveltyCollector(long byteLimit, int cellLimit) {
		if (byteLimit<1) throw new IllegalArgumentException("Novelty byte limit must be positive");
		if (cellLimit<1) throw new IllegalArgumentException("Novelty cell limit must be positive");
		this.byteLimit=byteLimit;
		this.cellLimit=cellLimit;
	}

	@Override
	public void accept(Ref<ACell> ref) {
		ACell cell=ref.getValue();
		if (cell==null || cell.isEmbedded()) return;
		long estimate=cell.getEncodingLength()+Format.MAX_VLQ_LONG_LENGTH
			+Ref.INDIRECT_ENCODING_LENGTH;
		if (estimate>byteLimit) {
			omitted++;
			return;
		}
		cells.addLast(cell);
		estimatedBytes+=estimate;
		while (cells.size()>cellLimit || estimatedBytes>byteLimit) {
			ACell removed=cells.removeFirst();
			estimatedBytes-=removed.getEncodingLength()+Format.MAX_VLQ_LONG_LENGTH
				+Ref.INDIRECT_ENCODING_LENGTH;
			omitted++;
		}
	}

	ArrayList<ACell> getCells() {
		return new ArrayList<>(cells);
	}

	long getEstimatedBytes() {
		return estimatedBytes;
	}

	long getOmittedCount() {
		return omitted;
	}
}
