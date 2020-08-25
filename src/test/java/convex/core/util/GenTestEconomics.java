package convex.core.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.runner.RunWith;

import com.pholser.junit.quickcheck.Property;
import com.pholser.junit.quickcheck.runner.JUnitQuickcheck;

@RunWith(JUnitQuickcheck.class)
public class GenTestEconomics {

	@Property
	public void alwaysARate(Long a, Long b) {
		if ((a>0)&&(b>0)) {
			double rate=Economics.swapRate(a, b);
			assertTrue(rate>0);
		}
	}
	
	@Property
	public void zeroCostsZero(Long a, Long b) {
		if ((a>0)&&(b>0)) {
			long price = Economics.swapPrice(0, a, b);
			
			// price should always be 1, since must spend minimum 1 unit to increase pool
			assertEquals(1L,price);
		}
	}
}
