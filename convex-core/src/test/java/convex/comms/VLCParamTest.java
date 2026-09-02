package convex.comms;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Arrays;
import java.util.Collection;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import convex.core.data.ACell;
import convex.core.data.Blob;
import convex.core.data.Cells;
import convex.core.data.Format;
import convex.core.data.FormatFuzzTest;
import convex.core.data.prim.CVMLong;
import convex.core.exceptions.BadFormatException;
import convex.core.lang.RT;
import convex.test.Samples;

public class VLCParamTest {

	public static Collection<Object[]> dataExamples() {
		return Arrays.asList(new Object[][] { { 0L }, { 63L }, { 64L }, { -63L }, { -64L }, { -65L }, { 1234L },
				{ 1234578 }, { -1234578 }, { CVMLong.create(1) }, { CVMLong.create(255) }, { Long.MAX_VALUE }, { Long.MIN_VALUE },
				{ Integer.MAX_VALUE }, { Integer.MIN_VALUE },
//			{ BigInteger.valueOf(Long.MAX_VALUE).multiply(BigInteger.TEN) },
//			{ BigInteger.valueOf(Long.MIN_VALUE).multiply(BigInteger.TEN) },

		});
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("dataExamples")
	public void testRoundTrip(Object source) throws BadFormatException {
		// create using CVM-coerced values
		ACell value = RT.cvm(source);
		Blob b = Cells.encode(value);
		ACell v2 = Samples.TEST_STORE.decode(b);
		assertEquals(value, v2);

		if (value instanceof CVMLong) {
			CVMLong cl=(CVMLong) value;
			// check length after tag
			assertEquals(1 + Format.getLongLength(cl.longValue()), b.count());
		}

		FormatFuzzTest.doMutationTest(b);
	}
}
