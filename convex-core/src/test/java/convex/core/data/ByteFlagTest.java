package convex.core.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import convex.core.cvm.CVMEncoder;
import convex.core.cvm.CVMTag;
import convex.core.data.prim.AByteFlag;
import convex.core.data.prim.ByteFlag;
import convex.core.data.prim.CVMBool;
import convex.core.exceptions.BadFormatException;
import convex.core.lang.RT;
import convex.core.lang.Reader;
import convex.test.Samples;

public class ByteFlagTest {

	@Test 
	public void testAllByteFlags() throws BadFormatException, IOException {
		for (int i=0; i<16; i++) {
			doByteFlagTest(i);
		}
	}
	
	@Test 
	public void testInavlidValues() {
		assertNull(AByteFlag.create(-1));
		assertNull(AByteFlag.create(16));
		assertNull(AByteFlag.create(CVMTag.TRUE)); // we expect 1, not 0xb1 !
		
		// sneaky checks where the low byte is 0
		assertNull(AByteFlag.create(0x100000000l)); 	
		assertNull(AByteFlag.create(Long.MIN_VALUE)); 
	}
	
	@Test public void testBooleans() {
		ByteFlag bf=ByteFlag.create(0);
		ByteFlag bt=ByteFlag.create(1);
		
		assertNotSame(bf,CVMBool.FALSE);
		assertEquals(bf,CVMBool.FALSE);
		assertEquals(CVMBool.FALSE,bf);

		assertEquals(bt,CVMBool.TRUE);
		assertEquals(CVMBool.TRUE,bt);

	}

	@ParameterizedTest
	@ValueSource(ints = {0, 1})
	public void testBooleanHashCode(int value) throws BadFormatException {
		CVMBool bool=CVMBool.create(value==1);
		for (AByteFlag flag : booleanRepresentations(value)) {
			ACell decoded=CVMEncoder.INSTANCE.decode(flag.getEncoding());
			assertSame(bool,decoded);
			assertEquals(flag,decoded);
			assertEquals(decoded,flag);
			assertEquals(flag.getEncoding(),decoded.getEncoding());
			assertEquals(flag.getHash(),decoded.getHash());
			assertEquals(flag.hashCode(),decoded.hashCode());
		}
	}

	@ParameterizedTest
	@ValueSource(ints = {0, 1})
	public void testBooleanHashCollections(int value) {
		AByteFlag[] representations=booleanRepresentations(value);
		// Check both directions: either representation may be stored or looked up.
		for (AByteFlag stored : representations) {
			for (AByteFlag lookup : representations) {
				HashMap<ACell,String> map=new HashMap<>();
				map.put(stored,"original");
				assertEquals("original",map.get(lookup));
				assertEquals("original",map.put(lookup,"replacement"));
				assertEquals(1,map.size());
				assertEquals("replacement",map.remove(stored));
				assertTrue(map.isEmpty());

				HashSet<ACell> set=new HashSet<>();
				assertTrue(set.add(stored));
				assertTrue(set.contains(lookup));
				assertFalse(set.add(lookup));
				assertEquals(1,set.size());
				assertTrue(set.remove(lookup));
				assertTrue(set.isEmpty());
			}
		}
	}

	private static AByteFlag[] booleanRepresentations(int value) {
		CVMBool bool=CVMBool.create(value==1);
		return new AByteFlag[] {bool, ByteFlag.create(value),
			ByteFlag.forTag(bool.getTag()), new ByteFlag(bool.getTag())};
	}

	private void doByteFlagTest(int i) throws BadFormatException, IOException {
		AByteFlag b=AByteFlag.create(i);
		byte tag=b.getTag();
		
		assertEquals(Tag.BYTE_FLAG_BASE+i,tag);
		assertEquals(i,tag&0x0f); // value is low hex digit of tag
		
		// Test the encoding. Not much to do, but important!
		Blob enc=b.getEncoding();
		assertEquals(1,enc.count());
		assertEquals(tag,enc.byteAt(0));
		
		String rd="#["+enc.toHexString()+"]";
		if (i>=2) {
			// Printing for non-boolean byte flags
			assertEquals(rd,RT.print(b).toString());
		}
		assertSame(b,Reader.read(rd));
		
		// The CVM decoder returns singletons. A cache may instead retain an equal
		// generic CAD3 ByteFlag, including the alternative representation of 0/1.
		assertSame(b,CVMEncoder.INSTANCE.decode(enc));
		Cells.persist(ByteFlag.create(i),Samples.TEST_STORE);
		assertEquals(b,Samples.TEST_STORE.decode(enc));
		
		ObjectsTest.doAnyValueTests(b);
	}
}
