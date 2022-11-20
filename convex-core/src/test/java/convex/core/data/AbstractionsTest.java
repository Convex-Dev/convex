package convex.core.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.Test;

import convex.core.data.prim.CVMBool;
import convex.core.data.prim.CVMLong;
import convex.core.lang.ACVMTest;
import convex.core.lang.RT;

/**
 * Class for tests of data structure abstraction invariants.
 * 
 * Most important as proofs of consistency
 */
public class AbstractionsTest extends ACVMTest {

	@Test public void testAssocGet() {
		doAssocGetTest(Sets.EMPTY,CVMLong.ONE,CVMBool.TRUE);
		doAssocGetTest(Maps.empty(),CVMLong.ONE,CVMLong.ZERO);
		doAssocGetTest(Maps.of(0,1),CVMLong.ONE,CVMLong.ZERO);
		doAssocGetTest(Vectors.of(1,2,3),CVMLong.ONE,CVMBool.FALSE);
		doAssocGetTest(BlobMaps.empty(),Blob.EMPTY,CVMLong.ZERO);
	}

	private void doAssocGetTest(ADataStructure<?> a, ACell key, ACell value) {
		ADataStructure<?> b=a.assoc(key, value);
		assertSame(b.getType(),a.getType());
		assertEquals(value,RT.get(b, key));
	}
	
	@Test public void testSingleton() {
		doSingletonTest(Maps.of(2,18));
		doSingletonTest(Vectors.of(13));
		doSingletonTest(Lists.of(13));
		doSingletonTest(Sets.of(19));
		doSingletonTest(BlobMaps.create(Blob.fromHex("cafebabe"), CVMLong.MAX_VALUE));
	}

	private void doSingletonTest(ADataStructure<?> a) {
		assertEquals(1,a.count());
		ACell e=a.get(0);
		assertEquals(a,a.empty().conj(e));
		// TODO: Depends on Map slicing
		// assertSame(a.empty(),a.slice(1));
	}
	
	
}
