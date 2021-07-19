package convex.util;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import convex.core.util.Huge;
import convex.core.util.UMath;

public class HugeTest {

	
	@Test public void testMul() {
		Huge h1=Huge.multiply(1, -1);
		assertEquals(-1,h1.hi);
		assertEquals(-1,h1.lo);
		
		Huge h2=Huge.multiply(0x100000000L, 0x100000000L);
		assertEquals(1,h2.hi);
		assertEquals(0,h2.lo);
	}
	
	@Test public void testConstants() {
		Huge zero=Huge.ZERO;
		Huge one=Huge.ONE;
		assertEquals(zero, zero.add(0));
		assertEquals(zero, one.sub(one));
	}
	
	@Test public void testAddRegression() {
		long b=-212944119L;
		Huge hb=Huge.create(b);
		Huge s3=Huge.ZERO.add(b);
		assertEquals(hb,s3);
	}
	
	@Test public void testUnsignedCarry() {
		assertEquals(0L,UMath.unsignedAddCarry(1L, 1L));
		assertEquals(1L,UMath.unsignedAddCarry(-1L, -1L));
		assertEquals(0L,UMath.unsignedAddCarry(-1L, 0));
		assertEquals(0L,UMath.unsignedAddCarry(0, -1L));
		assertEquals(1L,UMath.unsignedAddCarry(Long.MIN_VALUE, Long.MIN_VALUE));
	}
	

}
