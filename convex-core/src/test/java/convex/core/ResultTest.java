package convex.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import convex.core.data.ACell;
import convex.core.data.AHashMap;
import convex.core.data.Keyword;
import convex.core.data.Keywords;
import convex.core.data.Maps;
import convex.core.data.RecordTest;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;
import convex.core.exceptions.ResultException;
import convex.core.lang.RT;

public class ResultTest {

	@Test
	public void testBasicResult() {
		Result r1=Result.create(RT.cvm(0L),Vectors.of(1,2,3).toVector());
		assertNull(r1.getTrace());
		assertNull(r1.getInfo());
		
		assertEquals(r1,r1.updateRefs(r->r));
		
		RecordTest.doRecordTests(r1);

	}
	
	@Test
	public void testExtraInfo() {
		Result r1=Result.create(CVMLong.ZERO,Vectors.of(1,2,3));
		Result r2=r1.withExtraInfo(Maps.of(Keywords.FOO,CVMLong.ONE));
		assertNotEquals(r1,r2);
		
		Result r3=r2.withExtraInfo(null);
		assertSame(r2,r3);
	}
	
	@Test
	public void testResultCreation() {
		AHashMap<Keyword,ACell> info=Maps.of(Keywords.TRACE,Vectors.empty());
		Result r1=Result.create(CVMLong.create(0L),RT.cvm(1L),ErrorCodes.FATAL,info);
		assertSame(Vectors.empty(),r1.getTrace());
		assertSame(info,r1.getInfo());
		assertSame(ErrorCodes.FATAL,r1.getErrorCode());
		
		RecordTest.doRecordTests(r1);
	}
	
	@Test public void fromException() {
		assertEquals(ErrorCodes.EXCEPTION,Result.fromException(null).getErrorCode());
		
		// This should round trip efficiently
		Result a=Result.error(Keywords.FOO, "Blah").withSource(SourceCodes.CLIENT);
		assertSame(a,Result.fromException(new ResultException(a)));
	}
	
	@Test
	public void testBadBuild() {
		assertThrows(IllegalArgumentException.class,()->Result.buildFromVector(Vectors.of(1,2,3,4,5,6,7)));
		assertThrows(IllegalArgumentException.class,()->Result.buildFromVector(Vectors.empty()));
	}

}
