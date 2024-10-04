package convex.core.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;

import org.junit.runner.RunWith;

import com.pholser.junit.quickcheck.Property;
import com.pholser.junit.quickcheck.runner.JUnitQuickcheck;

@RunWith(JUnitQuickcheck.class)
public class GenTestEconomics {

	@Property
	public void testPools(Long a, Long b, Long c) {
		// a and b are pool args, c is a trade amount of a
		a=Math.abs(a);
		b=Math.abs(b);
		
		if ((a<=0)||(b<=0)) {
			return;
		}
		
		// There should always be a positive rate for any configuration of pool
		{
			double rate=Economics.swapRate(a, b);
			assertTrue(rate>0);
		}
		
		// price should always be 1, since must spend minimum 1 unit to increase pool
		long priceOfZero = Economics.swapPrice(0, a, b);
		assertTrue(priceOfZero>0);
		
		// Positive buy of A
		if ((c>0)&&(c<a)) {
			long buyPrice = (long) Economics.swapPrice(0, a, b);
			assertTrue(buyPrice>0);
			
		}
	}
	
	@Property
	public void testMulDiv(Long a, Long b, Long c) {
		a=natural(a);
		b=natural(b);
		c=natural(c);
		
		BigInteger exact=BigInteger.valueOf(a).multiply(BigInteger.valueOf(b)).divide(BigInteger.valueOf(c));
		if (exact.bitLength()<=63) {
			assertTrue(exact.bitLength()<=63);
			long expected=exact.longValue();
			assertEquals(expected,Utils.slowMulDiv(a, b, c));
			assertEquals(expected,Utils.fastMulDiv(a, b, c));
			assertEquals(expected,Utils.mulDiv(a, b, c));
		}
	}

	private Long natural(Long a) {
		if (a>=0) return a;
		if (a==Long.MIN_VALUE) return 0L;
		return Math.abs(a);
	}

}
