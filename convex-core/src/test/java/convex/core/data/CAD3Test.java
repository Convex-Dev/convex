package convex.core.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestInstance.Lifecycle;

import convex.core.cvm.Address;
import convex.core.cvm.CVMTag;
import convex.core.cvm.Context;
import convex.core.data.prim.ByteFlag;
import convex.core.data.prim.CVMBool;
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
		assertSame(Core.VECTOR,Reader.read("#["+Utils.toHexString(CVMTag.CORE_DEF)+"01]"));
	}
	
	@Test public void testAddressExtension() {
		Address a=Address.create(127);
		ExtensionValue e=ExtensionValue.create((byte) CVMTag.ADDRESS, 127);
		assertEquals(a,e);
		assertEquals(e,a);
	}
	
	@Test public void testByteFlags() {
		ByteFlag bf=ByteFlag.create(2);
		assertEquals(bf,Reader.read("#[b2]"));
		ObjectsTest.doAnyValueTests(bf);

	}

	
	@Test public void testReadEncodings() {
		assertSame(Address.ZERO,Reader.read("#[EA00]"));
		assertSame(CVMLong.ZERO,Reader.read("#[10]"));
		assertEquals(CVMBool.TRUE,Reader.read("#[b1]"));
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
			assertSame(v,dr.values());
			String ds="#[df03110111021103]";
			assertEquals(ds,dr.toString());
			assertEquals(dr,Reader.read(ds));

			assertTrue(dr.isCompletelyEncoded());

			ObjectsTest.doAnyValueTests(dr);
		}
		
		{ // Empty vector DenseRecord
			DenseRecord ed=DenseRecord.create(0xDF,Vectors.empty());
			assertEquals(Blob.fromHex("df00"),ed.getEncoding());
			
			ObjectsTest.doAnyValueTests(ed);
		}
		
		{ // Large vector DenseRecord
			AVector<CVMLong> v=Samples.INT_VECTOR_300;
			DenseRecord dr=DenseRecord.create(0xDF,v);
			assertEquals((byte)0xdf,dr.getTag());
			assertFalse(dr.isCompletelyEncoded());
			
			assertEquals(300,dr.count());
			assertSame(v,dr.values());
			
			ObjectsTest.doAnyValueTests(dr);
		}
	}
	
	@Test public void testCodedValues() {
		CodedValue vc=CodedValue.create(0xc9,CVMLong.ONE,null);
		
		assertEquals(vc,Reader.read("#[c9110100]"));
		
		ObjectsTest.doAnyValueTests(vc);
	}
	
	/**
	 * Tests for dense record interactions with core functions
	 */
	@Test public void testCoreRecords() {
		Context ctx=context();
		ctx=exec(ctx,"(def dr #[d603110111021103])"); // would be a Block, but too many elements
		DenseRecord dr=ctx.getResult();
		assertNotNull(dr);
		
		// DenseRecord behaves like a map
		assertCVMEquals(3,eval(ctx,"(count dr)"));
		assertCVMEquals(Vectors.of(1,2,3),eval(ctx,"(values dr)"));
		assertTrue(evalB(ctx,"(map? dr)"));
		
		assertFalse(evalB(ctx,"(vector? dr)"));
	}

}
