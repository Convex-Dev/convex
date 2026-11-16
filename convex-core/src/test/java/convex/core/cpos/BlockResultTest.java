package convex.core.cpos;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import convex.core.Result;
import convex.core.cvm.Keywords;
import convex.core.data.ACell;
import convex.core.data.AVector;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;
import convex.core.exceptions.BadFormatException;
import convex.core.exceptions.InvalidDataException;
import convex.test.Samples;

/**
 * A BlockResult decoded from its encoding behaves as the one it was encoded from
 */
public class BlockResultTest {

	static BlockResult decode(BlockResult br) throws BadFormatException {
		return Samples.TEST_STORE.decode(br.getEncoding());
	}

	@Test
	public void testDecodedValidates() throws Exception {
		AVector<Result> results=Vectors.of(Result.value(CVMLong.ONE));
		decode(BlockResult.create(null, results)).validate();

		// An invalid Block may have no results
		decode(BlockResult.create(null, (AVector<Result>)null)).validate();

		BlockResult notResults=decode(new BlockResult(Vectors.of(null, Vectors.of(CVMLong.ONE))));
		assertThrows(InvalidDataException.class, ()->notResults.validate());

		BlockResult notVector=decode(new BlockResult(Vectors.of(null, CVMLong.ONE)));
		assertThrows(InvalidDataException.class, ()->notVector.validate());
	}

	@Test
	public void testDecodedFields() throws Exception {
		AVector<Result> results=Vectors.of(Result.value(CVMLong.ONE));
		BlockResult br=decode(BlockResult.create(null, results));
		assertEquals(results,br.get(Keywords.RESULTS));
		assertNull(br.get(Keywords.STATE));
		assertEquals(results,br.getResults());
	}

	@Test
	public void testDecodedEquality() throws Exception {
		ACell a=decode(BlockResult.create(null, Vectors.of(Result.value(CVMLong.ONE))));
		ACell b=decode(BlockResult.create(null, Vectors.of(Result.value(CVMLong.ZERO))));
		assertFalse(a.equals(b));
		assertEquals(a,decode(BlockResult.create(null, Vectors.of(Result.value(CVMLong.ONE)))));
	}
}
