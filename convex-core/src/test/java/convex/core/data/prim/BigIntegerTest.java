package convex.core.data.prim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigInteger;

import org.junit.jupiter.api.Test;

public class BigIntegerTest {

	@Test public void testBigIntegerAssumptions() {
		assertThrows(java.lang.NumberFormatException.class,()->new BigInteger(new byte[0]));
		assertEquals(BigInteger.ZERO,new BigInteger(new byte[1]));
	}
	
	@Test public void testZero() {
		CVMBigInteger bi=CVMBigInteger.create(new byte[0]);
		assertEquals(0,bi.longValue());
		assertEquals(0.0,bi.doubleValue());
		assertEquals(BigInteger.ZERO,bi.getBigInteger());
		
		doBigTest(bi);
	}

	private void doBigTest(CVMBigInteger bi) {
		// BigInteger value should be cached
		BigInteger big=bi.getBigInteger();
		assertSame(big,bi.getBigInteger());
		
		CVMBigInteger bi2=CVMBigInteger.create(big);
		assertEquals(bi,bi2);
	}
}
