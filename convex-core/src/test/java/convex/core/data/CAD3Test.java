package convex.core.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestInstance.Lifecycle;

import convex.core.cvm.Context;
import convex.core.data.prim.CVMLong;
import convex.core.lang.ACVMTest;
import convex.core.lang.Core;
import convex.core.lang.Reader;
import convex.core.util.Utils;
import convex.test.Samples;

import static convex.test.Assertions.*;

@TestInstance(Lifecycle.PER_CLASS)
public class CAD3Test extends ACVMTest {
	
	@Test public void testExtensionValues() {
		ExtensionValue ev=ExtensionValue.create((byte) 0xe3,100);
		assertEquals(100,ev.longValue());
		assertEquals(Tag.EXTENSION_VALUE_BASE+3,ev.getTag());
		assertEquals("#[e364]",ev.toString());
		
		ObjectsTest.doAnyValueTests(ev);
	}
	
	@Test public void testExtensionCoreDefs() {
		assertSame(Core.VECTOR,Reader.read("#["+Utils.toHexString(Tag.CORE_DEF)+"01]"));
	}
	
	@Test public void testReadEncodings() {
		assertSame(Address.ZERO,Reader.read("#[2100]"));
		assertSame(CVMLong.ZERO,Reader.read("#[10]"));
		assertSame(Vectors.empty(),Reader.read("#[8000]"));
		assertNull(Reader.read("#[00]"));
		assertEquals(ExtensionValue.create((byte) 0xe5, 0),Reader.read("#[e500]"));
	}
	
	@Test public void testDenseRecords() {
		{ // Small vector DenseRecord
			AVector<ACell> v=Vectors.of(1,2,3);
			DenseRecord dr=DenseRecord.create(0xDF,v);
			assertEquals(Blob.fromHex("df03110111021103"),dr.getEncoding());
	
			assertEquals(3,dr.count);
			assertSame(v,dr.toVector());
			assertEquals("#[df03110111021103]",dr.toString());
			
			ObjectsTest.doAnyValueTests(dr);
		}
		
		{ // Empty vector DenseRecord
			DenseRecord ed=DenseRecord.create(0xDE,Vectors.empty());
			assertEquals(Blob.fromHex("de00"),ed.getEncoding());
			
			ObjectsTest.doAnyValueTests(ed);
		}
		
		{ // Large vector DenseRecord
			AVector<CVMLong> v=Samples.INT_VECTOR_300;
			DenseRecord dr=DenseRecord.create(0xDF,v);
			assertEquals((byte)0xdf,dr.getTag());
			
			assertEquals(300,dr.count());
			assertSame(v,dr.toVector());
			
			ObjectsTest.doAnyValueTests(dr);
		}
	}
	
	/**
	 * Tests for dense record interactions with core functions
	 */
	@Test public void testCoreRecords() {
		Context ctx=context();
		ctx=exec(ctx,"(def dr #[df03110111021103])");
		DenseRecord dr=ctx.getResult();
		assertNotNull(dr);
		
		// DenseRecord behaves like a sequence
		assertCVMEquals(3,eval(ctx,"(count dr)"));
		assertCVMEquals(1,eval(ctx,"(first dr)"));
		assertCVMEquals(Vectors.of(1,2,3),eval(ctx,"(vec dr)"));
		assertCVMEquals(Vectors.of(1,2,3,4),eval(ctx,"(conj dr 4)"));
		
		assertFalse(evalB(ctx,"(vector? dr)"));
		assertFalse(evalB(ctx,"(map? dr)"));
	}

}
