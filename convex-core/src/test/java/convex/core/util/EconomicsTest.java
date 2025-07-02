package convex.core.util;

import static org.junit.Assert.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigInteger;
import java.text.DecimalFormat;

import org.junit.jupiter.api.Test;

public class EconomicsTest {

	@Test
	public void testPoolRate() {

		assertEquals(1.0, Economics.swapRate(100, 100));
		assertEquals(1.0, Economics.swapRate(Long.MAX_VALUE, Long.MAX_VALUE));
		assertEquals(2.0, Economics.swapRate(1, 2));

		assertThrows(IllegalArgumentException.class, () -> Economics.swapRate(0, 0));
		assertThrows(IllegalArgumentException.class, () -> Economics.swapRate(1000, 0));
		assertThrows(IllegalArgumentException.class, () -> Economics.swapRate(0, 1000));
		assertThrows(IllegalArgumentException.class, () -> Economics.swapRate(-10, 10));
		assertThrows(IllegalArgumentException.class, () -> Economics.swapRate(10, -10));
		assertThrows(IllegalArgumentException.class, () -> Economics.swapRate(Long.MIN_VALUE, Long.MIN_VALUE));
	}

	@Test
	public void testPoolPrice() {

		checkSwap(101, 50, 100, 100);
		checkSwap(-33, -50, 100, 100);
		checkSwap(1000000, 999999, 1000000, 1);
		
		// buying zero costs one because of strict pool increase
		checkSwap(1, 0, 1675, 117);
		checkSwap(1, 0, 12, 1454517);
		checkSwap(1, 0, 100, 100);
		
		// selling into pool at zero price
		checkSwap(0, -1, 1, 1);

		// Overflow casess
		assertEquals(Long.MAX_VALUE, Economics.swapPrice(Long.MAX_VALUE-1, Long.MAX_VALUE, 10));
		assertEquals(Long.MAX_VALUE, Economics.swapPrice(1, 10, Long.MAX_VALUE));
		
		// Trying to buy entire pool
		assertThrows(IllegalArgumentException.class, () -> Economics.swapPrice(1, 1, 1));
		
		// This sale would blow the pool!
		assertThrows(IllegalArgumentException.class, () -> Economics.swapPrice(-1, Long.MAX_VALUE, 10));
		
		// TODO: seem to be some instability issues doing things like this?
		// assertEquals(Long.MAX_VALUE-1000000, Economics.swapPrice(Long.MAX_VALUE-1000000, Long.MAX_VALUE, 1000000));

		assertThrows(IllegalArgumentException.class, () -> Economics.swapPrice(100, 100, 100));
		assertThrows(IllegalArgumentException.class, () -> Economics.swapPrice(100, 0, 100));
		assertThrows(IllegalArgumentException.class, () -> Economics.swapPrice(0, 0, 0));
		assertThrows(IllegalArgumentException.class, () -> Economics.swapPrice(100, 100, 0));
		assertThrows(IllegalArgumentException.class, () -> Economics.swapPrice(100, 50, 200));
	}
	
	private void checkSwap(long expected, long delta, long quantA, long quantB) {
		long price=Economics.swapPrice(delta, quantA, quantB);
		assertEquals(expected, price);
		
		BigInteger pool1 = BigInteger.valueOf(quantA).multiply(BigInteger.valueOf(quantB));
		BigInteger pool2 = BigInteger.valueOf(quantA-delta).multiply(BigInteger.valueOf(quantB+price));
		assertTrue(pool2.compareTo(pool1)>0);
	}

	public static void main(String[] args) {
		// Quick benchmark to prove fast version is faster
		// Seems about 5-10x faster in most cases? 
		// Worst case 2-3x faster (big numbers approaching 2^63)
		// Though the main point is avoiding GC pressure anyway :-)
		DecimalFormat formatter = new DecimalFormat("0.00000");	
		
		System.out.println("Fast");
		BenchUtils.benchMark(10,()->{
			for (int i=1; i<=100000; i++) {
				long x=Bits.xorshift64(i)>>>1;
				long y=Bits.xorshift64(x)>>>1;
				long z=x^y+1;
				Utils.fastMulDiv(x, y, z);
			}
		},t->System.out.println(formatter.format(t)));
		
		System.out.println("Slow");
		BenchUtils.benchMark(10,()->{
			for (int i=1; i<=100000; i++) {
				long x=Bits.xorshift64(i)>>>1;
				long y=Bits.xorshift64(x)>>>1;
				long z=x^y+1;
				Utils.slowMulDiv(x, y, z);
			}
		},t->System.out.println(formatter.format(t)));
	}
}
