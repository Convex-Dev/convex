package convex.util;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.runner.RunWith;

import com.pholser.junit.quickcheck.Property;
import com.pholser.junit.quickcheck.runner.JUnitQuickcheck;

import convex.core.util.Huge;

@RunWith(JUnitQuickcheck.class)
public class GenTestHuge {

	@Property
	public void pairOps(Long a, Long b) {
		Huge ha=Huge.create(a);
		Huge hb=Huge.create(b);
		
		// multiplication
		Huge p = Huge.multiply(a, b);
		assertEquals(p.hi,Math.multiplyHigh(a, b));
		assertEquals(p.lo,a*b);
		
		Huge p0=Huge.multiply(a, 0);
		assertEquals(Huge.ZERO,p0);

		Huge p1=Huge.multiply(a, 1);
		assertEquals(ha,p1);

		// addition
		Huge s=Huge.add(a,b);
		assertEquals(s.lo,a+b);
		assertEquals(s,ha.add(hb));
		
		Huge s2=s.add(-b);
		assertEquals(ha,s2);
		
		
	}
	
	@Property
	public void singleOps(Long a) {
		Huge ha=Huge.create(a);
		
		Huge hneg=ha.negate();
		
		assertEquals(ha,Huge.ZERO.add(a));
		assertEquals(ha,Huge.ZERO.add(ha));

		assertEquals(ha,Huge.multiply(1L,a));
		assertEquals(Huge.ZERO,Huge.multiply(0L,a));

		assertEquals(ha,hneg.negate());
		assertEquals(Huge.ZERO,ha.add(hneg));
		
		// TODO: fix 128-bit mul
		// Huge h2=ha.mul(ha);
		// assertEquals(h2,hneg.mul(hneg));

	}
	

}
