package convex.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import convex.core.cvm.Address;
import convex.core.data.ACell;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;
import convex.core.util.CAIP;


public class CAIPTest {

	
	@Test public void testCVM() {
		assertTrue(CAIP.isCVM("slip44:864"));
		assertFalse(CAIP.isCVM("CVM"));
		assertFalse(CAIP.isCVM("foo"));
	}
	
	@Test public void testTokenIDs() {
		assertEquals(Vectors.of(Address.create(100), CVMLong.ZERO),CAIP.parseTokenID("cad29:100-0"));
		
		doCAD29Test("cad29:30");
		doCAD29Test("cad29:30-1");
		doCAD29Test("cad29:30-foo");
	}

	private void doCAD29Test(String caip19) {
		ACell id=CAIP.parseTokenID(caip19);
		
		String tid=CAIP.toAssetID(id) ;
		
		assertEquals(caip19,tid);
	}
}
