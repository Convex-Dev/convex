package convex.util;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.Collection;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import convex.core.data.AArrayBlob;
import convex.core.data.Blob;
import convex.core.data.prim.BigIntegerTest;
import convex.core.data.prim.CVMBigInteger;
import convex.core.util.Utils;

public class BigIntegerParamTest {

	public static Collection<Object[]> dataExamples() {
		return Arrays.asList(new Object[][] {
			{ "Zero", BigInteger.ZERO },
			{ "Max Long", BigInteger.valueOf(Long.MAX_VALUE) },
			{ "Min Long", BigInteger.valueOf(Long.MIN_VALUE) },
			{ "Short hex string CAFEBABE", Utils.hexToBigInt("CAFEBABE") },
			{ "A big number", Utils.hexToBigInt(
					"506bc1dc099358e5137292f4efdd57e400f29ba5132aa5d12b18dac1c1f6aaba645c0b7b58158babbfa6c6cd5a48aa7340a8749176b120e8516216787a13dc76") },
			{ "Negative big number", Utils.hexToBigInt(
					"506bc1dc099358e5137292f4efdd57e400f29ba5132aa5d12b18dac1c1f6aaba645c0b7b58158babbfa6c6cd5a48aa7340a8749176b120e8516216787a13dc76")
							.negate() } });
	}

	@ParameterizedTest(name = "{index}: {0}")
	@MethodSource("dataExamples")
	public void testHexRoundTrip(String label, BigInteger num) {
		if (num.signum() < 0) return;
		String s = Utils.toHexString(num, (num.bitLength() / 4 + 2) & 0xFFFE);
		AArrayBlob d = Blob.fromHex(s);
		byte[] bs = d.getBytes();
		BigInteger b = new BigInteger(1, bs);
		assertEquals(num, b);
	}

	@ParameterizedTest(name = "{index}: {0}")
	@MethodSource("dataExamples")
	public void testCVMBigInteger(String label, BigInteger num) {
		CVMBigInteger bi=CVMBigInteger.wrap(num);
		assertEquals(num,bi.getBigInteger());

		BigIntegerTest.doBigTest(bi);
	}
}
