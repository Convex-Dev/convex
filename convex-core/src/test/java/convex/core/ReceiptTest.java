package convex.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

import convex.core.cvm.Receipt;
import convex.core.data.Blob;
import convex.core.data.RecordTest;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;

public class ReceiptTest {
	
	@Test public void testExamples() {
		doReceiptTest(Receipt.create(null));
		doReceiptTest(Receipt.create(true,ErrorCodes.TRUST,Vectors.empty()));
		
		doReceiptTest(Receipt.create(false,CVMLong.ONE,Vectors.of(Vectors.empty())));
	}
	
	@Test public void testEmpty() {
		Receipt r = Receipt.create(null);
		assertEquals(r,r);
		assertFalse(r.isError());
		assertNull(r.getResult());
		
		assertEquals(Blob.fromHex("D800"),r.getEncoding());
	}
	
	public void doReceiptTest (Receipt r) {
		RecordTest.doRecordTests(r);
	}

}
