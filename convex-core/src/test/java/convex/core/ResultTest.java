package convex.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import convex.core.cvm.Keywords;
import convex.core.data.ACell;
import convex.core.data.AHashMap;
import convex.core.data.Blob;
import convex.core.data.Format;
import convex.core.data.Keyword;
import convex.core.data.Maps;
import convex.core.data.RecordTest;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;
import convex.core.exceptions.BadFormatException;
import convex.core.exceptions.ResultException;
import convex.core.lang.RT;
import convex.core.lang.Reader;

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
	
	@Test public void testDecodeRegression() throws BadFormatException {
		String bad="{:id 4,:result \"Can't convert argument at position 1 (with type Keyword) to type Number\",:error :CAST,:log [],:info {:trace [\"In core function: +\" \"In expression: (+ :foo 3)\"],:eaddr #11,:fees 2690,:tx 0x7f61e6e8abc68199638a8502336f4f5e98334e2ec5391e9693c099270f8f17f1,:source :CODE,:juice 545,:loc [1 0]}}";
		Result r=Result.fromData(Reader.read(bad));
		Blob b=Format.encodeMultiCell(r, true);
		// assertEquals("0xad051104304743616e277420636f6e7665727420617267756d656e7420617420706f736974696f6e20312028776974682074797065204b6579776f72642920746f2074797065204e756d626572330443415354800020d71ec156f7a11f1ba988b92a800eb57169e25a04289fd24bc0c892c3d1d5787d811582073305747261636580023013496e20636f72652066756e6374696f6e3a202b3019496e2065787072657373696f6e3a20282b203a666f6f20332933056561646472210b330466656573120a823302747831207f61e6e8abc68199638a8502336f4f5e98334e2ec5391e9693c099270f8f17f13306736f757263653304434f444533056a7569636512022133036c6f638002110110",b.toString());
		Result r2=Format.decodeMultiCell(b);
		assertEquals(r,r2);
		
	}

}
