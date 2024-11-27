package convex.comms;

import static org.junit.Assert.assertEquals;

import java.util.Arrays;
import java.util.Collection;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import convex.core.data.ACell;
import convex.core.data.Blob;
import convex.core.data.Cells;
import convex.core.data.Format;
import convex.core.data.FuzzTestFormat;
import convex.core.data.prim.CVMLong;
import convex.core.exceptions.BadFormatException;
import convex.core.lang.RT;

@RunWith(Parameterized.class)
public class VLCParamTest {
	private ACell value;

	public VLCParamTest(Object value) {
		// create using CVM-coerced values
		this.value = RT.cvm(value);
	}

	@Parameterized.Parameters(name = "{0}")
	public static Collection<Object[]> dataExamples() {
		return Arrays.asList(new Object[][] { { 0L }, { 63L }, { 64L }, { -63L }, { -64L }, { -65L }, { 1234L },
				{ 1234578 }, { -1234578 }, { CVMLong.create(1) }, { CVMLong.create(255) }, { Long.MAX_VALUE }, { Long.MIN_VALUE },
				{ Integer.MAX_VALUE }, { Integer.MIN_VALUE },
//			{ BigInteger.valueOf(Long.MAX_VALUE).multiply(BigInteger.TEN) },
//			{ BigInteger.valueOf(Long.MIN_VALUE).multiply(BigInteger.TEN) },

		});
	}

	@Test
	public void testRoundTrip() throws BadFormatException {
		Blob b = Cells.encode(value);
		ACell v2 = Format.read(b);
		assertEquals(value, v2);

		if (value instanceof CVMLong) {
			CVMLong cl=(CVMLong) value;
			// check length after tag
			assertEquals(1 + Format.getLongLength(cl.longValue()), b.count());
		}

		FuzzTestFormat.doMutationTest(b);
	}
}
