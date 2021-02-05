package convex.core.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;
import java.util.Set;

import org.junit.jupiter.api.Test;

import convex.core.crypto.Hash;
import convex.core.data.prim.CVMLong;
import convex.core.exceptions.InvalidDataException;
import convex.core.exceptions.MissingDataException;
import convex.core.lang.RT;
import convex.core.lang.Symbols;
import convex.core.util.Utils;
import convex.test.Samples;

public class RefTest {
	@Test
	public void testMissingData() {
		// create a Ref using just a bad hash
		Ref<?> ref = Ref.forHash(Samples.BAD_HASH);

		// equals comparison should work
		assertEquals(ref, Ref.forHash(Samples.BAD_HASH));

		// gneric properties of missing Ref
		assertEquals(Samples.BAD_HASH, ref.getHash());
		assertEquals(Ref.UNKNOWN, ref.getStatus()); // shouldn't know anything about this Ref yet
		assertFalse(ref.isDirect());

		// we expect a failure here
		assertThrows(MissingDataException.class, () -> ref.getValue());
	}

	@Test
	public void testRefSet() {
		// 10 element refs
		assertEquals(10, Ref.accumulateRefSet(Samples.INT_VECTOR_10).size());
		assertEquals(10, Utils.totalRefCount(Samples.INT_VECTOR_10));

		// 256 element refs, 16 tree branches
		assertEquals(272, Ref.accumulateRefSet(Samples.INT_VECTOR_256).size());
		assertEquals(272, Utils.totalRefCount(Samples.INT_VECTOR_256));

		// 11 = 10 element refs plus one for enclosing ref
		assertEquals(11, Ref.accumulateRefSet(Samples.INT_VECTOR_10.getRef()).size());
	}

	@Test
	public void testShallowPersist() {
		Blob bb = Blob.createRandom(new Random(), 100); // unique blob too big to embed
		AVector<ACell> v = Vectors.of(bb,bb,bb,bb); // vector containing big blob four times. Shouldn't be embedded.
		Hash bh = bb.getHash();
		Hash vh = v.getHash();
		
		Ref<AVector<ACell>> ref = v.getRef().persistShallow();
		assertEquals(Ref.STORED, ref.getStatus());

		assertThrows(MissingDataException.class, () -> Ref.forHash(bh).getValue());
		
		assertFalse(v.isEmbedded());
		assertEquals(v, Ref.forHash(vh).getValue());
	}

	@Test
	public void testEmbedded() {
		assertTrue(Ref.get(RT.cvm(1L)).isEmbedded()); // a primitive
		assertTrue(Ref.NULL_VALUE.isEmbedded()); // singleton null ref
		assertTrue(Ref.EMPTY_VECTOR.isEmbedded()); // singleton null ref
		assertFalse(Blob.create(new byte[Format.MAX_EMBEDDED_LENGTH]).getRef().isEmbedded()); // too big to embed
		assertTrue(Samples.LONG_MAP_10.getRef().isEmbedded()); // a ref container
	}

	@Test
	public void testPersistEmbeddedNull() throws InvalidDataException {
		Ref<ACell> nr = Ref.get(null);
		assertSame(Ref.NULL_VALUE, nr);
		assertSame(nr, nr.persist());
		nr.validate();
		assertTrue(nr.isEmbedded());
	}

	@Test
	public void testPersistEmbeddedLong() {
		Object val=RT.cvm(10001L);
		Ref<ACell> nr = Ref.get(val);
		assertSame(nr.getValue(), nr.persist().getValue());
		assertTrue(nr.isEmbedded());
	}
	
	@Test
	public void testGoodData() {
		AVector<ASymbolic> value = Vectors.of(Keywords.FOO, Symbols.FOO);
		// a good ref
		Ref<?> orig = value.getRef();
		assertEquals(Ref.UNKNOWN, orig.getStatus());
		assertFalse(orig.isPersisted());
		orig = orig.persist();
		assertTrue(orig.isPersisted());

		// a ref using the same hash
		if (!(value.isEmbedded())) {
			Ref<?> ref = Ref.forHash(orig.getHash());
			assertEquals(orig, ref);
			assertEquals(value, ref.getValue());
		}
	}

	@Test
	public void testCompare() {
		assertEquals(0, Ref.get(RT.cvm(1L)).compareTo(Ref.createPersisted(RT.cvm(1L))));
		assertEquals(1, Ref.get(RT.cvm(1L)).compareTo(
				Ref.forHash(Hash.fromHex("0000000000000000000000000000000000000000000000000000000000000000"))));
		assertEquals(-1, Ref.get(RT.cvm(1L)).compareTo(
				Ref.forHash(Hash.fromHex("ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff"))));
	}
	
	@Test
	public void testVectorRefCounts() {
		AVector<CVMLong> v=Vectors.of(1,2,3);
		assertEquals(3,v.getRefCount());
		
		AVector<CVMLong> zv=Vectors.repeat(CVMLong.create(0), 16);
		assertEquals(16,zv.getRefCount());
		
		// 3 tail elements after prefix ref
		AVector<CVMLong> zvv=zv.concat(v);
		assertEquals(4,zvv.getRefCount());

	}

	@Test
	public void testDiabolicalDeep() {
		Ref<ACell> a = Samples.DIABOLICAL_MAP_2_10000.getRef();
		// TODO: consider if this should be possible, currently not (stack overflow)
		// Ref.accumulateRefSet(a);
		assertTrue(a.isEmbedded());
	}

	@Test
	public void testDiabolicalWide() {
		Ref<ACell> a = Samples.DIABOLICAL_MAP_30_30.getRef();
		// OK since we manage de-duplication
		Set<Ref<?>> set = Ref.accumulateRefSet(a);
		assertEquals(31 + 30 * 16, set.size()); // 16 refs at each level after de-duping
		assertFalse(a.isEmbedded());
	}

	@Test
	public void testNullRef() {
		Ref<?> nullRef = Ref.get(null);
		assertNotNull(nullRef);
		assertSame(nullRef.getHash(), Hash.NULL_HASH);
	}
}
