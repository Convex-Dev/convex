package convex.comms;

import static org.junit.Assert.assertEquals;

import java.util.Arrays;
import java.util.Collection;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import convex.core.data.Blob;
import convex.core.data.Format;
import convex.core.data.FuzzTestFormat;
import convex.core.data.Tag;
import convex.core.data.prim.CVMByte;
import convex.core.exceptions.BadFormatException;

@RunWith(Parameterized.class)
public class VLCParamTest {
	private Object value;

	public VLCParamTest(Object value) {
		this.value = value;
	}

	@Parameterized.Parameters(name = "{0}")
	public static Collection<Object[]> dataExamples() {
		return Arrays.asList(new Object[][] { { 0L }, { 63L }, { 64L }, { -63L }, { -64L }, { -65L }, { 1234 },
				{ 1234578 }, { -1234578 }, { CVMByte.create(1) }, { CVMByte.create(255) }, { Long.MAX_VALUE }, { Long.MIN_VALUE },
				{ Integer.MAX_VALUE }, { Integer.MIN_VALUE },
//			{ BigInteger.valueOf(Long.MAX_VALUE).multiply(BigInteger.TEN) },
//			{ BigInteger.valueOf(Long.MIN_VALUE).multiply(BigInteger.TEN) },

		});
	}

	@Test
	public void testRoundTrip() throws BadFormatException {
		Blob b = Format.encodedBlob(value);
		Object v2 = Format.read(b);
		assertEquals(value, v2);

		if (value instanceof Long) {
			assertEquals(Tag.LONG, b.get(0)); // check correct tag
			assertEquals(1 + Format.getVLCLength((long) value), b.length()); // check length after tag
		}

		FuzzTestFormat.doMutationTest(b);
	}
}
