package convex.db.calcite;

import java.util.Iterator;

import convex.core.data.ABlob;
import convex.core.data.ACell;
import convex.core.data.AVector;
import convex.core.data.Index;
import convex.db.lattice.RowBlock;
import convex.db.lattice.SQLRow;
import convex.db.lattice.SQLSchema;
import convex.db.lattice.SQLTable;

/**
 * Enumerator that performs a lazy full table scan over a block-packed row index.
 *
 * <p>Iterates the outer block Index one block at a time, decoding each block's
 * bytes once into a row buffer, then draining that buffer row by row.
 *
 * <p>Two traversal paths:
 * <ul>
 *   <li><b>blockVec path</b> — when slot 4 of the table state holds an
 *       {@code AVector<ACell>} of live block blobs. Uses
 *       {@link AVector#iterator()} for O(n)-total traversal (one VectorTree
 *       descent rather than one per element).</li>
 *   <li><b>Index path</b> — sequential {@code entryAt(i)} traversal of the
 *       row block Index; also O(n) total via the trie's sequential iterator.</li>
 * </ul>
 */
public class ConvexTableEnumerator extends ConvexEnumerator {

	/** Non-null when blockVec path is active; stored to allow reset(). */
	private final AVector<ACell> blockVec;
	/** O(n)-total iterator over blockVec; reset()-able by re-creating from blockVec. */
	private Iterator<ACell> blockVecIter;
	/** Fallback Index path (used when blockVec == null). */
	private final Index<ABlob, ACell> blockIndex;
	private final int colCount;
	private final long blockCount;

	private long blockPos = -1;
	@SuppressWarnings("unchecked")
	private AVector<ACell>[] blockRowBuf = new AVector[0];
	private int blockRowBufSize = 0;
	private int rowPos = 0;

	public ConvexTableEnumerator(SQLSchema tables, String tableName) {
		SQLTable table = tables.getLiveTable(tableName);
		if (table == null) {
			this.blockVec = null;
			this.blockVecIter = null;
			this.blockIndex = Index.none();
			this.colCount = 0;
			this.blockCount = 0;
			return;
		}
		AVector<ACell> bv = table.getBlockVec();
		if (bv != null) {
			// blockVec path: iterator is O(n) total (visits each VectorTree node once)
			this.blockVec = bv;
			this.blockVecIter = bv.iterator();
			this.blockIndex = null;
			this.blockCount = bv.count();
		} else {
			this.blockVec = null;
			this.blockVecIter = null;
			Index<ABlob, ACell> rows = table.getRows();
			this.blockIndex = rows != null ? rows : Index.none();
			this.blockCount = this.blockIndex.count();
		}
		AVector<AVector<ACell>> schema = table.getSchema();
		this.colCount = schema != null ? (int) schema.count() : 0;
	}

	@Override
	public boolean moveNext() {
		while (true) {
			// Drain rows buffered from the current block
			while (rowPos < blockRowBufSize) {
				AVector<ACell> row = blockRowBuf[rowPos++];
				if (!SQLRow.isLive(row)) continue;
				ACell[] arr = SQLRow.getValues(row).toCellArray();
				if (arr.length < colCount) {
					ACell[] padded = new ACell[colCount];
					System.arraycopy(arr, 0, padded, 0, arr.length);
					arr = padded;
				}
				currentRow = arr;
				return true;
			}
			// Advance to next block — decode it once into blockRowBuf
			blockPos++;
			if (blockPos >= blockCount) {
				currentRow = null;
				return false;
			}
			ACell block = (blockVecIter != null)
					? blockVecIter.next()
					: blockIndex.entryAt(blockPos).getValue();
			int n = RowBlock.isBlock(block) ? RowBlock.count(block) : 0;
			if (n > blockRowBuf.length) {
				@SuppressWarnings("unchecked")
				AVector<ACell>[] newBuf = new AVector[n];
				blockRowBuf = newBuf;
			}
			blockRowBufSize = 0;
			rowPos = 0;
			final int[] idx = {0};
			RowBlock.forEach(block, (pk, row) -> blockRowBuf[idx[0]++] = row);
			blockRowBufSize = idx[0];
		}
	}

	@Override
	public void reset() {
		blockPos = -1;
		blockRowBufSize = 0;
		rowPos = 0;
		currentRow = null;
		if (blockVec != null) blockVecIter = blockVec.iterator();
	}
}
