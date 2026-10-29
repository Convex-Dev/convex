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
	
	@Test public void testCodedValueEqualityCachesNothing() {
		CodedValue c1=CodedValue.create(0xC5, CVMLong.create(123456789), Vectors.of(1,2,3));
		CodedValue c2=CodedValue.create(0xC5, CVMLong.create(123456789), Vectors.of(1,2,3));
		CodedValue c3=CodedValue.create(0xC5, CVMLong.create(123456789), Vectors.of(1,2,4));
		CodedValue c4=CodedValue.create(0xC6, CVMLong.create(123456789), Vectors.of(1,2,3));
		assertEqualityCachesNothing(c1,c2,c3);
		assertFalse(c1.equals(c4)); // same children, different tag
		assertFalse(c4.equals(c1));
	}

	@Test public void testDenseRecordEqualityCachesNothing() {
		DenseRecord d1=DenseRecord.create(0xDF, Vectors.of(CVMLong.create(123456789), Vectors.of(1,2,3)));
		DenseRecord d2=DenseRecord.create(0xDF, Vectors.of(CVMLong.create(123456789), Vectors.of(1,2,3)));
		DenseRecord d3=DenseRecord.create(0xDF, Vectors.of(CVMLong.create(123456789), Vectors.of(1,2,4)));
		DenseRecord d4=DenseRecord.create(0xDE, Vectors.of(CVMLong.create(123456789), Vectors.of(1,2,3)));
		assertEqualityCachesNothing(d1,d2,d3);
		assertFalse(d1.equals(d4)); // same data, different tag
		assertFalse(d4.equals(d1));
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
		
		assertEquals(Core.QUOTE,Reader.read("#[ed00]"));
		assertEquals(ExtensionValue.create(CVMTag.CORE_DEF,1280),Reader.read("#[ed8a00]"));
	}
	
	@Test public void testDenseRecords() {
		// A record sharing the Order tag may contain arbitrary child references.
		ObjectsTest.doAnyValueTests(DenseRecord.create(CVMTag.ORDER,
				Vectors.of(CVMLong.ZERO, Samples.INT_VECTOR_300, Vectors.empty())));
		ObjectsTest.doAnyValueTests(DenseRecord.create(CVMTag.ORDER,
				Vectors.of(CVMLong.ZERO, Vectors.of(Samples.INT_VECTOR_300), Vectors.empty())));
		ObjectsTest.doAnyValueTests(DenseRecord.create(CVMTag.ORDER,
				Vectors.of(Samples.INT_VECTOR_300, Vectors.empty(), Vectors.empty())));
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

		// Generic coded values must retain unresolved refs until the children in
		// a multi-cell message are read, even when sharing the CVM opcode tag.
		for (byte tag : new byte[] {(byte)0xc9, CVMTag.OP_CODED}) {
			ObjectsTest.doAnyValueTests(CodedValue.create(tag, Samples.INT_VECTOR_300, null));
			ObjectsTest.doAnyValueTests(CodedValue.create(tag, Samples.INT_VECTOR_300, Samples.INT_VECTOR_256));
		}
		ObjectsTest.doAnyValueTests(CodedValue.create(0xc9, CVMLong.ONE, Samples.INT_VECTOR_300));
	}
	
	/**
	 * Tests for dense record interactions with core functions
	 */
	@Test public void testCoreRecords() {
		Context ctx=context();
		ctx=exec(ctx,"(def dr #[d603110111021103])"); // would be a Block, but too many elements
		DenseRecord dr=ctx.getResult();
		assertNotNull(dr);
		
		// Unrecognised CAD3 records retain their bytes but have no CVM map schema.
		assertCastError(step(ctx,"(count dr)"));
		assertCastError(step(ctx,"(values dr)"));
		assertFalse(evalB(ctx,"(map? dr)"));
		
		assertFalse(evalB(ctx,"(vector? dr)"));
	}

}
