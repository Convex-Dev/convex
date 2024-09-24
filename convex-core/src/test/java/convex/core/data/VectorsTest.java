package convex.core.data;

import static convex.test.Assertions.assertCVMEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ListIterator;
import java.util.Spliterator;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

import convex.core.data.prim.CVMLong;
import convex.core.data.util.VectorBuilder;
import convex.core.exceptions.BadFormatException;
import convex.core.lang.RT;
import convex.core.lang.Symbols;
import convex.test.Samples;

/**
 * Example based tests for vectors.
 * 
 * Also doVectorTests(...) implements generic tests for any vector.
 */
public class VectorsTest {

	@Test
	public void testEmptyVector() {
		AVector<AString> e = Vectors.empty();
		RefTest.checkInternal(e);
		AArrayBlob d = e.getEncoding();
		assertArrayEquals(new byte[] { Tag.VECTOR, 0 }, d.getBytes());
		assertSame(e,Vectors.empty());
		assertSame(e,Vectors.wrap(Cells.EMPTY_ARRAY));
		assertSame(e,Vectors.create(new ACell[0]));
		assertSame(e,Vectors.create(new ACell[0],0,0));
	}

	@Test
	public void testSubVectors() {
		AVector<CVMLong> v = Samples.INT_VECTOR_300;

		AVector<CVMLong> v1 = v.subVector(10, Vectors.CHUNK_SIZE);
		assertEquals(VectorLeaf.class, v1.getClass());
		assertEquals(RT.cvm(10), v1.get(0));

		AVector<CVMLong> v2 = v.subVector(10, Vectors.CHUNK_SIZE * 2);
		assertEquals(VectorTree.class, v2.getClass());
		assertEquals(RT.cvm(10), v2.get(0));

		AVector<CVMLong> v3 = v.subVector(10, Vectors.CHUNK_SIZE * 2 - 1);
		assertEquals(VectorLeaf.class, v3.getClass());
		assertEquals(RT.cvm(10), v3.get(0));

		AVector<CVMLong> v4 = v3.conj(RT.cvm(1000L));
		assertEquals(VectorTree.class, v4.getClass());
		assertEquals(RT.cvm(26), v4.get(16));
		assertEquals(v1, v4.subVector(0, Vectors.CHUNK_SIZE));
	}

	@Test
	public void testCreateSpecialCases() {
		assertSame(Vectors.empty(), VectorLeaf.create(new ACell[0]));
		assertSame(Vectors.empty(), VectorLeaf.create(new ACell[10], 3, 0));
	}
	
	@Test 
	public void testUnsafeCreate() {
		doVectorTests(VectorTree.unsafeCreate(32, Samples.INT_VECTOR_16,Samples.INT_VECTOR_16));
	}

	@Test
	public void testChunks() {
		assertEquals(Samples.INT_VECTOR_16, Samples.INT_VECTOR_300.getChunk(0));
		AVector<CVMLong> v = Samples.INT_VECTOR_300.getChunk(0);
		assertEquals(VectorTree.class, v.getChunk(0).concat(v).getClass());
	}
	
	@Test
	public void testMaxBranches() {
		// non-embedded vector with 16 elements 
		AVector<ACell> nonEmbed=Vectors.repeat(Samples.INT_VECTOR_10, 16);
		assertFalse(nonEmbed.isEmbedded());
		
		// embedded tail with 4 branches, count 64
		AVector<ACell> tail=nonEmbed.concat(nonEmbed).concat(nonEmbed).concat(nonEmbed);
		assertEquals(4,tail.getBranchCount());
		assertEquals(64,tail.count());
		assertTrue(tail.isEmbedded());

		
		AVector<ACell> bigEmbed=Vectors.repeat(tail,16);
		assertEquals(16,bigEmbed.getRefCount());
		assertEquals(64,bigEmbed.getBranchCount()); // maximum for vector?
		
		AVector<ACell> v=tail.concat(bigEmbed);
		assertFalse(v.isCompletelyEncoded());
		
		doVectorTests(nonEmbed);
		doVectorTests(bigEmbed);
		doVectorTests(v);
	}

	@Test
	public void testChunkConcat() {
		VectorLeaf<CVMLong> v = Samples.INT_VECTOR_300.getChunk(16);
		AVector<CVMLong> vv = v.concat(v);
		assertEquals(VectorTree.class, vv.getClass());
		assertEquals(v, vv.getChunk(16));

		assertSame(Samples.INT_VECTOR_16, Samples.INT_VECTOR_16.empty().appendChunk(Samples.INT_VECTOR_16));

		assertThrows(IndexOutOfBoundsException.class, () -> vv.getChunk(3));

		// can't append chunk unless initial size is correct
		assertThrows(IllegalArgumentException.class, () -> Samples.INT_VECTOR_10.appendChunk(Samples.INT_VECTOR_16));
		assertThrows(IllegalArgumentException.class, () -> Samples.INT_VECTOR_300.appendChunk(Samples.INT_VECTOR_16));

		
		VectorLeaf<CVMLong> tooSmall=VectorLeaf.create(new CVMLong[] {CVMLong.create(1),CVMLong.create(2)});
		// can't append wrong chunk size
		assertThrows(IllegalArgumentException.class,
				() -> Samples.INT_VECTOR_16.appendChunk(tooSmall));

	}

	@Test
	public void testIndexOf() {
		AVector<CVMLong> v = Samples.INT_VECTOR_300;
		CVMLong last=v.get(v.count()-1);
		assertEquals(299, v.indexOf(last));
		assertEquals(299L, v.longIndexOf(last));
		assertEquals(299, v.lastIndexOf(last));
		assertEquals(299L, v.longLastIndexOf(last));

		CVMLong mid=v.get(29);
		assertEquals(29, v.indexOf(mid));
		assertEquals(29L, v.longIndexOf(mid));
		assertEquals(29, v.lastIndexOf(mid));
		assertEquals(29L, v.longLastIndexOf(mid));

	}

	@Test
	public void testAppending() {
		int SIZE = 300;
		@SuppressWarnings("unchecked")
		AVector<CVMLong> lv = (VectorLeaf<CVMLong>) VectorLeaf.EMPTY;

		for (int i = 0; i < SIZE; i++) {
			CVMLong ci=RT.cvm(i);
			lv = lv.append(ci);
			assertEquals(i + 1L, lv.count());
			assertEquals(ci, lv.get(i));
		}
		assertEquals(300L, lv.count());
	}

	@Test
	public void testBigMatch() {
		AVector<CVMLong> v = Samples.INT_VECTOR_300;
		assertTrue(v.anyMatch(i -> i.longValue() == 3));
		assertTrue(v.anyMatch(i -> i.longValue() == 299));
		assertFalse(v.anyMatch(i -> i.longValue() == -1));

		assertFalse(v.allMatch(i -> i.longValue() == 3));
		assertTrue(v.allMatch(i -> i.longValue() >=0));
	}

	@Test
	public void testAnyMatch() {
		AVector<CVMLong> v = Vectors.of(1, 2, 3, 4);
		assertTrue(v.anyMatch(i -> i.longValue() == 3));
		assertFalse(v.anyMatch(i -> i.longValue() == 5));
	}

	@Test
	public void testAllMatch() {
		AVector<CVMLong> v = Vectors.of(1, 2, 3, 4);
		assertTrue(v.allMatch(i -> i instanceof CVMLong));
		assertFalse(v.allMatch(i -> i.longValue() < 3));
	}

	@Test
	public void testMap() {
		AVector<CVMLong> v = Vectors.of(1, 2, 3, 4);
		AVector<CVMLong> exp = Vectors.of(2, 3, 4, 5);
		assertEquals(exp, v.map(i -> CVMLong.create(i.longValue() + 1)));
	}

	@Test
	public void testSmallAssoc() {
		AVector<CVMLong> v = Vectors.of(1, 2, 3, 4);
		AVector<CVMLong> nv = v.assoc(2, RT.cvm(10L));
		assertEquals(Vectors.of(1, 2, 10, 4), nv);
	}

	@Test
	public void testBigAssoc() {
		AVector<CVMLong> v = Samples.INT_VECTOR_300;
		AVector<CVMLong> nv = v.assoc(100, RT.cvm(17L));
		assertEquals(RT.cvm(17L), nv.get(100));
	}

	@Test
	public void testReduce() {
		AVector<CVMLong> vec = Vectors.of(1, 2, 3, 4);
		assertEquals(110, (long) vec.reduce((s, v) -> s + v.longValue(), 100L));
	}

	@Test
	public void testMapEntry() {
		AVector<CVMLong> v1 = Vectors.of(1L, 2L);
		
		MapEntry<CVMLong,CVMLong> me=MapEntry.of(1L,2L);
		assertEquals(v1, me);
		assertEquals(v1, me.toVector());
		assertEquals(me,me.toVector());	
		doVectorTests(me);
	}

	@Test
	public void testLastIndex() {
		// regression test
		AVector<CVMLong> v = Samples.INT_VECTOR_300.concat(Vectors.of(1, null, 3, null));
		assertEquals(303L, v.longLastIndexOf(null));
	}

	@Test
	public void testReduceBig() {
		AVector<CVMLong> vec = Samples.INT_VECTOR_300;
		assertEquals(100 + (299 * 300) / 2, vec.reduce((s, v) -> s + v.longValue(), 100L));
	}
	
	// TODO: more sensible tests on embedded vector sizes
	@Test 
	public void testEmbedding() {
		// should embed, little values
		AVector<CVMLong> vec = Vectors.of(1, 2, 3, 4);
		assertTrue(vec.isEmbedded());
		assertEquals(10L,vec.getEncoding().count());
		
		// should embed, small enough
		AVector<ACell> vec2=Vectors.of(vec,vec);
		assertTrue(vec2.isEmbedded());
		assertEquals(22L,vec2.getEncoding().count());

		AVector<ACell> vec3=Vectors.of(vec2,vec2,vec2,vec2,vec2,vec2,vec2,vec2);
		assertFalse(vec3.isEmbedded());
	}

	@Test
	public void testUpdateRefs() {
		AVector<CVMLong> vec = Vectors.of(1, 2, 3, 4);
		AVector<CVMLong> vec2 = vec.updateRefs(r -> CVMLong.create(1L+((CVMLong)r.getValue()).longValue()).getRef());
		assertEquals(Vectors.of(2, 3, 4, 5), vec2);
	}
	
	@Test 
	public void testVectorArray() {
		
		doVectorTests(VectorArray.of());
		
		doVectorTests(VectorArray.of(1,2,3));
		
		AVector<CVMLong> vc=Vectors.of(0,1,2,3,4,5,6,7,8,9,10,11,12,13,14,15);
	
		AVector<CVMLong> v2=vc.appendChunk(vc);
		assertEquals(vc, v2.slice(16));
	}
	
	@Test
	public void testVectorBuilder() {
		VectorBuilder<CVMLong> vb=new VectorBuilder<CVMLong>();
		assertSame(Vectors.empty(), vb.toVector());
		vb.conj(CVMLong.ZERO);
		assertEquals(Vectors.of(0),vb.toVector());
		
		vb.concat(Vectors.of(1,2));
		assertEquals(Vectors.of(0,1,2),vb.toVector());
	}
	
	@Test
	public void testVectorBuilderLarge() {
		VectorBuilder<CVMLong> vb=new VectorBuilder<CVMLong>();
		for (int i=0; i<10; i++) {
			vb.concat(vb.toVector());
			vb.conj(CVMLong.create(i));
		}
		
		AVector<CVMLong> v=vb.toVector();
		assertEquals(1023,v.count());
	}

	@Test
	public void testNext() {
		AVector<CVMLong> v1 = Samples.INT_VECTOR_256;
		AVector<CVMLong> v2 = v1.next();
		assertEquals(v1.get(1), v2.get(0));
		assertEquals(v1.get(255), v2.get(254));
		assertEquals(1L, v1.count() - v2.count());
		
		// Null if no elements remain
		assertNull(Vectors.empty().next());
		assertNull(Vectors.of(1).next());
	}

	@Test
	public void testIterator() {
		int SIZE = 100;
		@SuppressWarnings("unchecked")
		AVector<CVMLong> lv = (VectorLeaf<CVMLong>) VectorLeaf.EMPTY;

		for (int i = 0; i < SIZE; i++) {
			lv = lv.append(RT.cvm(i));
			assertTrue(lv.isCanonical());
		}
		assertEquals(4950L, lv.reduce((acc, v) -> acc + v.longValue(), 0L));

		// forward iteration
		ListIterator<CVMLong> it = lv.listIterator();
		Spliterator<CVMLong> split = lv.spliterator();
		AtomicLong splitAcc = new AtomicLong(0);
		for (int i = 0; i < SIZE; i++) {
			assertTrue(it.hasNext());
			assertTrue(split.tryAdvance(a -> splitAcc.addAndGet(a.longValue())));
			assertEquals(i, it.nextIndex());
			assertEquals(RT.cvm(i), it.next());
		}
		assertEquals(100, it.nextIndex());
		assertEquals(4950, splitAcc.get());
		assertFalse(it.hasNext());

		// backward iteration
		ListIterator<CVMLong> li = lv.listIterator(SIZE);
		for (int i = SIZE - 1; i >= 0; i--) {
			assertTrue(li.hasPrevious());
			assertEquals(i, li.previousIndex());
			assertCVMEquals(i, li.previous());
		}
		assertEquals(-1, li.previousIndex());
		assertFalse(li.hasPrevious());
	}

	@Test
	public void testEmptyVectorHash() {
		AVector<?> e = Vectors.empty();

		// test the byte layout of the empty vector
		assertEquals(e.getEncoding(), Blob.fromHex("8000"));
		assertEquals(e.getHash(), Vectors.of((Object[])new VectorLeaf<?>[0]).getHash());
	}

	@Test
	public void testSmallVectorSerialisation() {
		// test the byte layout of the vector
		// value should be an long encoded to two bytes (0x1101)
		assertEquals(Blob.fromHex("80011101"), Vectors.of(1).getEncoding());

		// value should be a negative long encoded to two bytes (0x11ff)
		assertEquals(Blob.fromHex("800111ff"), Vectors.of(-1).getEncoding());
	}

	@Test
	public void testPrefixLength() throws BadFormatException {
		assertEquals(2, Vectors.of(1, 2, 3).commonPrefixLength(Vectors.of(1, 2)));
		assertEquals(2, Vectors.of(1, 2).commonPrefixLength(Vectors.of(1, 2, 8)));
		assertEquals(0, Vectors.of(1, 2, 3).commonPrefixLength(Vectors.of(2, 2, 3)));

		AVector<CVMLong> v1 = Vectors.of(0, 1, 2, 3, 4, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1,
				1, 1, 1);
		assertEquals(5, v1.commonPrefixLength(Samples.INT_VECTOR_300));
		assertEquals(5, Samples.INT_VECTOR_300.commonPrefixLength(v1));
		assertEquals(v1.count(), v1.commonPrefixLength(v1));

		assertEquals(10, Samples.INT_VECTOR_10.commonPrefixLength(Samples.INT_VECTOR_23));
		assertEquals(256, Samples.INT_VECTOR_300.commonPrefixLength(Samples.INT_VECTOR_256));
		assertEquals(256, Samples.INT_VECTOR_300.commonPrefixLength(Samples.INT_VECTOR_256.append(RT.cvm(17L))));
	}
	
	@Test
	public void testPrefixLengthBuilding() throws BadFormatException {
		AVector<ACell> v1=Vectors.empty();
		AVector<ACell> v2=Vectors.empty();
		for (int i=0; i<=300; i++) {
			assertEquals(i,v1.commonPrefixLength(v2));
			
			ACell d=CVMLong.create(i+1000);
			AVector<ACell> v1d=v1.conj(d);
			AVector<ACell> v2d=v1.conj(CVMLong.create(i+1000));
			assertEquals(i,v1.commonPrefixLength(v2d));
			assertEquals(i,v2.conj((ACell)Symbols.FOO).commonPrefixLength(v2d));
			
			v1=v1d;
			v2=v2d;
		}
	}

	/**
	 * Generic tests for any vector
	 * @param v Any Vector
	 */
	public static <T extends ACell> void doVectorTests(AVector<T> v) {
		long n = v.count();

		if (n == 0) {
			assertSame(Vectors.empty(), v.getCanonical());
		} else {
			T last = v.get(n - 1);
			T first = v.get(0);
			assertEquals(n - 1, v.longLastIndexOf(last));
			assertEquals(0L, v.longIndexOf(first));

			AVector<T> v2 = v.append(first);
			assertEquals(first, v2.get(n));
		}

		assertEquals(v.toVector(), Vectors.of(v.toArray()));

		CollectionsTest.doSequenceTests(v);
	}

}
