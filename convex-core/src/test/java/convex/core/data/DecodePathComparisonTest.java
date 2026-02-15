package convex.core.data;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import convex.core.cvm.CVMEncoder;
import convex.core.data.AEncoder.DecodeState;

/**
 * Tests that cells decoded via DecodeState re-encode to identical bytes.
 * If re-encoding differs, the hash will differ, breaking multi-cell ref
 * resolution in decodeMultiCell.
 */
public class DecodePathComparisonTest {

	static final CVMEncoder enc = CVMEncoder.INSTANCE;

	/**
	 * For each child cell in the belief multi-cell blob:
	 * 1. Decode via DecodeState (no encoding attached)
	 * 2. Re-encode the decoded cell
	 * 3. Compare re-encoded bytes with original bytes
	 *
	 * Any mismatch means the DecodeState path produces a cell
	 * that encodes differently, causing hash mismatch in resolveRefs.
	 */
	@Test
	public void testChildCellReencoding() throws Exception {
		Blob beliefBlob = convex.core.cpos.BeliefSnapshotTest.getBeliefBlob();

		DecodeState ds = new DecodeState(beliefBlob);

		// Skip top cell
		ACell topCell = enc.read(ds);
		assertNotNull(topCell);

		// Check each child cell
		int childCount = 0;
		int mismatches = 0;
		while (ds.pos < ds.limit) {
			long encLength = enc.readVLQCount(ds);
			int childStart = ds.pos;
			int childEnd = childStart + (int) encLength;

			// Original encoding bytes for this child
			Blob originalEncoding = Blob.wrap(ds.data, childStart, (int) encLength);

			// Decode via DecodeState
			ACell child = enc.read(ds);
			assertEquals(childEnd, ds.pos, "DecodeState pos mismatch at child #" + childCount);
			assertNotNull(child, "Child should not be null");

			// Re-encode the decoded cell
			Blob reEncoded = Cells.encode(child);

			if (!originalEncoding.equals(reEncoded)) {
				mismatches++;
				System.out.println("MISMATCH child #" + childCount
					+ " type=" + child.getClass().getSimpleName()
					+ " origLen=" + originalEncoding.count()
					+ " reEncLen=" + reEncoded.count()
					+ " origHash=" + originalEncoding.getContentHash().toHexString()
					+ " reEncHash=" + reEncoded.getContentHash().toHexString());
				// Show first few bytes of each
				int showLen = (int) Math.min(40, originalEncoding.count());
				System.out.println("  orig: " + originalEncoding.slice(0, showLen).toHexString());
				System.out.println("  reEnc: " + reEncoded.slice(0, Math.min(40, reEncoded.count())).toHexString());
			}
			childCount++;
		}
		System.out.println("Compared " + childCount + " children, " + mismatches + " mismatches");
		assertEquals(0, mismatches, "All child cells should re-encode identically");
	}
}
