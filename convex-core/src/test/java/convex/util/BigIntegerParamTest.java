package convex.util;

import static org.junit.Assert.assertEquals;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.Collection;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import convex.core.data.AArrayBlob;
import convex.core.data.Blob;
import convex.core.util.Utils;

@RunWith(Parameterized.class)
public class BigIntegerParamTest {
	private BigInteger num;

	public BigIntegerParamTest(String label, BigInteger num) {
		this.num = num;
	}

	@Parameterized.Parameters(name = "{index}: {0}")
	public static Collection<Object[]> dataExamples() {
		return Arrays.asList(new Object[][] { { "Zero", BigInteger.ZERO },
				{ "Short hex string CAFEBABE", Utils.hexToBigInt("CAFEBABE") },
				{ "A big number", Utils.hexToBigInt(
						"506bc1dc099358e5137292f4efdd57e400f29ba5132aa5d12b18dac1c1f6aaba645c0b7b58158babbfa6c6cd5a48aa7340a8749176b120e8516216787a13dc76") },
				{ "Negative big number", Utils.hexToBigInt(
						"506bc1dc099358e5137292f4efdd57e400f29ba5132aa5d12b18dac1c1f6aaba645c0b7b58158babbfa6c6cd5a48aa7340a8749176b120e8516216787a13dc76")
						.negate() } });
	}

	@Test
	public void testHexRoundTrip() {
		if (num.signum() < 0) return;
		String s = Utils.toHexString(num, (num.bitLength() / 4 + 2) & 0xFFFE);
		AArrayBlob d = Blob.fromHex(s);
		byte[] bs = d.getBytes();
		BigInteger b = new BigInteger(1, bs);
		assertEquals(num, b);
	}

}
