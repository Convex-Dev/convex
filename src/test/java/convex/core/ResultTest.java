package convex.core;

import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.Test;

import convex.core.data.RecordTest;
import convex.core.data.Vectors;

public class ResultTest {

	@Test
	public void testBasicResult() {
		Result r1=Result.create(0L,Vectors.of(1,2,3));
		RecordTest.doRecordTests(r1);
		
		assertSame(r1,r1.updateRefs(r->r));
	}

}
