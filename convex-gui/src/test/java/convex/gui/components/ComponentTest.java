package convex.gui.components;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

import convex.core.data.prim.CVMLong;

public class ComponentTest {

	@Test public void testBalanceLabel() {
		BalanceLabel bl=new BalanceLabel();
		assertNull(bl.getBalance());
		
		bl.setDecimals(2);
		
		bl.setText("   3.444");
		assertEquals(344,bl.getBalance().longValue());
	}
	
	@Test public void testDecimalAmountField() {
		DecimalAmountField af=new DecimalAmountField(3);
		assertEquals(CVMLong.ZERO,af.getAmount());
	
		af.setText("   3.444");
		assertEquals(3444,af.getAmount().longValue());
		
		af.setText("   3.11111111");
		assertEquals(3111,af.getAmount().longValue());

		af.setText("401   ");
		assertEquals(401000,af.getAmount().longValue());

		af.setText("   401.2  ");
		assertEquals(401200,af.getAmount().longValue());
		
		af.setText("  fghrt  ");
		assertNull(af.getAmount());
	}
	
	@Test public void testDecimalAmountFieldNoDecimals() {
		DecimalAmountField af=new DecimalAmountField(0);
		assertEquals(CVMLong.ZERO,af.getAmount());
	
		af.setText("   3.444");
		assertEquals(3,af.getAmount().longValue());
		
		af.setText("1234.0");
		assertEquals(1234,af.getAmount().longValue());

		af.setText("  0.0001  ");
		assertEquals(0,af.getAmount().longValue());

		af.setText(".");
		assertNull(af.getAmount());

		af.setText("13.");
		assertEquals(13,af.getAmount().longValue());

	}
}
