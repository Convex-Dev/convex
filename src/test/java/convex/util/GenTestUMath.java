package convex.util;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.runner.RunWith;

import com.pholser.junit.quickcheck.Property;
import com.pholser.junit.quickcheck.runner.JUnitQuickcheck;

import convex.core.util.UMath;

@RunWith(JUnitQuickcheck.class)
public class GenTestUMath {

	
	@Property
	public void singleOps(Long a) {
		long mulHigh=UMath.multiplyHigh(a, a);
		
		assertEquals(mulHigh,UMath.multiplyHigh(-a, -a));
	}
	

}
