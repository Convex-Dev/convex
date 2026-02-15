package convex.core.data;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import convex.core.cvm.CVMEncoder;
import convex.core.cvm.GenesisStateTest;
import convex.core.cvm.SnapshotStateTest;
import convex.core.data.AEncoder.DecodeState;
import convex.core.exceptions.BadFormatException;
import convex.test.Samples;

/**
 * Tests that cells decoded via DecodeState produce identical results to
 * the Blob-based decode path: same encoding AND same Java type.
 *
 * The MultiFn bug showed that a cell can have correct encoding/hash but
 * wrong Java type (DenseRecord instead of MultiFn), causing CVM divergence.
 */
public class DecodePathComparisonTest {

	static final CVMEncoder enc = new CVMEncoder(Samples.TEST_STORE);

	@Test
	public void testBelief() throws Exception {
		Blob blob = convex.core.cpos.BeliefSnapshotTest.getBeliefBlob();
		checkMultiCellChildren(blob);
	}

	@Test
	public void testGenesisState() throws Exception {
		Blob blob = GenesisStateTest.getGenesisBlob();
		checkMultiCellChildren(blob);
	}

	@Test
	public void testConsensusState() throws Exception {
		ACell state = SnapshotStateTest.getConsensusState();
		Blob blob = Format.encodeMultiCell(state, true);
		checkMultiCellChildren(blob);
	}

	/**
	 * For each child cell in a multi-cell blob, decode via both paths and verify:
	 * 1. Re-encoding matches original bytes (hash correctness)
	 * 2. Java type matches between DecodeState and Blob decode paths
	 */
	private void checkMultiCellChildren(Blob multiCellBlob) throws Exception {
		DecodeState ds = new DecodeState(multiCellBlob);

		// Skip top cell
		ACell topCell = enc.read(ds);
		assertNotNull(topCell);

		int childCount = 0;
		while (ds.pos < ds.limit) {
			long encLength = enc.readVLQCount(ds);
			int childStart = ds.pos;
			int childEnd = childStart + (int) encLength;

			Blob originalEncoding = Blob.wrap(ds.data, childStart, (int) encLength);

			// Decode via DecodeState path
			ACell dsCell = enc.read(ds);
			assertEquals(childEnd, ds.pos, "DecodeState pos mismatch at child #" + childCount);
			assertNotNull(dsCell, "Child should not be null");

			// Check encoding round-trip
			Blob reEncoded = Cells.encode(dsCell);
			assertEquals(originalEncoding, reEncoded,
				"Re-encoding mismatch at child #" + childCount
				+ " type=" + dsCell.getClass().getSimpleName());

			// Check type match between DecodeState and Blob paths.
			// Blob decode may fail for cells with non-embedded refs (no store),
			// but for cells where it succeeds the types must match.
			try {
				ACell blobCell = enc.read(originalEncoding, 0);
				assertEquals(blobCell.getClass(), dsCell.getClass(),
					"Type mismatch at child #" + childCount
					+ ": Blob path=" + blobCell.getClass().getSimpleName()
					+ " DecodeState path=" + dsCell.getClass().getSimpleName());
			} catch (BadFormatException e) {
				// Expected for cells with non-embedded refs when no store is set
			}

			childCount++;
		}
		assertTrue(childCount > 0, "Should have at least one child cell");
	}
}
