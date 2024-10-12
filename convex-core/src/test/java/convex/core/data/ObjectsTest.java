package convex.core.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;

import convex.core.Constants;
import convex.core.data.Refs.RefTreeStats;
import convex.core.data.util.BlobBuilder;
import convex.core.exceptions.BadFormatException;
import convex.core.exceptions.InvalidDataException;
import convex.core.lang.RT;
import convex.core.store.AStore;
import convex.core.store.MemoryStore;
import convex.core.store.Stores;
import convex.core.util.Utils;
import convex.test.Samples;

/**
 * Generic test functions for arbitrary Data values.
 */
public class ObjectsTest {
	/**
	 * Generic tests for any valid Value (including null). Should pass for
	 * *any* non-partial cell where validate(...) succeeds.
	 * 
	 * @param a Value to test
	 */
	public static void doAnyValueTests(ACell a) {
		Hash h=Hash.get(a);
				
		try {
			a=Cells.persist(a);
		} catch (IOException e) {
			fail(e);
		}
		Ref<ACell> r = Ref.get(a);
		assertEquals(h,r.getHash());
		assertSame(a, r.getValue()); // shouldn't get GC'd because we have a strong reference

		doAnyEncodingTests(a);
		doCellTests(a);
	}




	/**
	 * Generic tests for an arbitrary valid Cell. May or may not be a valid CVM value. 
	 * 
	 * Checks required Cell properties across a number of themes.
	 * 
	 * @param a Cell to test
	 */
	public static void doCellTests(ACell a) {
		if (a==null) return;
		
		doCellEncodingTest(a);
		doCanonicalTests(a);	
		doHashTests(a);
		doEqualityTests(a);
		doCellValidationTests(a);
		doRefContainerTests(a);
		doCellRefTests(a);
		doPrintTests(a);
		doBranchTests(a);
		doMemorySizeTests(a);
	}
	
	private static void doBranchTests(ACell a) {
		int bc=a.getBranchCount();
		assertTrue(bc>=0);
		assertTrue(bc<=Cells.MAX_BRANCH_COUNT);
		
		// out of range branches should be null
		assertNull(a.getBranchRef(-1));
		assertNull(a.getBranchRef(bc));
		
		// bc of zero equivalent to completely encoded
		assertEquals(a.isCompletelyEncoded(),bc==0);
		
		int rc=a.getRefCount();
		if (rc==0) {
			// if no Refs, clearly none of them can be branches!
			assertEquals(0,bc);
		} else if (rc==1) {
			Ref<?> cr=a.getRef(0);
			if (cr.isEmbedded()) {
				assertEquals(bc,cr.branchCount());
			} else {
				assertEquals(1,bc);
			}
		}
		
		if (bc==0) {
			assertNull(a.getBranchRef(0));
			Cells.visitBranches(a, v->fail("Shouldn't visit any branch!"));
		} else {
			// branch refs within range should be non-null
			assertNotNull(a.getBranchRef(0));
			assertNotNull(a.getBranchRef(bc-1));
			
			int[] tmp=new int[1];
			Cells.visitBranches(a, v->{
				tmp[0]++;
				assertFalse(v.isEmbedded());
			});
			
			assertEquals(bc,tmp[0]);
		}
	}

	private static void doMemorySizeTests(ACell a) {
		long ms=a.calcMemorySize();
		long fms=Cells.storageSize(a);
		assertEquals(ms,a.getMemorySize());
		int rc=a.getRefCount();
		long elen=a.getEncodingLength();
		
		if (a.isEmbedded()) {
			if (rc==0) {
				// fully embedded, so memory size is zero and full memory sizing is just encoding length
				assertEquals(0L,ms);
				assertEquals(elen,fms);
			} else {
				// Full memory size is encoding length plus contained memory size
				assertEquals(elen+ms,fms);
			}

		} else {
			if (rc==0) {
				// no children, so memory size is element length plus overhead
				assertEquals(ms,elen+Constants.MEMORY_OVERHEAD);
			}
			// full memory size is always equal to memory size for non-embedded cells
			assertEquals(ms,fms);
		}
	}

	private static void doPrintTests(ACell a) {
		BlobBuilder bb=new BlobBuilder();
		assertFalse(a.print(bb,0)); // should always fail to print with limit of 0
		
		// Note we bail out for efficiency if short prints succeed
		if (doPrintTest(a,2)) return;
		if (doPrintTest(a,45)) return;
		if (doPrintTest(a,101)) return;
		if (doPrintTest(a,256)) return;
		if (doPrintTest(a,10000)) return;
	}

	/**
	 * Does a printing test up to the specified limit
	 * @param a
	 * @param limit
	 * @return True if fully printed, false otherwise
	 */
	private static boolean doPrintTest(ACell a, long limit) {
		BlobBuilder bb=new BlobBuilder();
		if (a.print(bb,limit)) {
			AString s=bb.getCVMString();
			long n=s.count();
			assertTrue(n<=limit); // should fit in length specified
			assertEquals(s,a.print(n));
			
			assertEquals(s,RT.print(a,n),()->"Expected print of length "+n+" for "+a); // must re-print in same length
			assertNull(RT.print(a,n-1)); // must fail with one less character in limit
			return true;
		} 
		return false;
	}

	private static void doCellValidationTests(ACell a) {
		try {
			a.validateCell();
			// doCellStorageTest(a); // TODO: Maybe fix after we have ACell.toDirect()
		} catch (InvalidDataException e) {
			throw Utils.sneakyThrow(e);
		}
	}


	@SuppressWarnings({ "rawtypes", "unchecked" })
	private static void doEqualityTests(ACell a) {
		assertTrue(a.equals(a));
		if (a instanceof Comparable) {
			Comparable c=(Comparable) a;
			assertEquals(0,c.compareTo(a));
		}
	}


	private static void doCellEncodingTest(ACell a) {
		Blob enc=a.getEncoding();
		EncodingTest.checkCodingSize(a);
		
		// Re=read on encoding
		ACell b;
		try {
			b = Format.read(enc);
		} catch (BadFormatException e) {
			fail("Reload from complete encoding failed for: " + a + " with encoding "+enc);
			return;
		}
		assertEquals(a,b);
		assertEquals(enc,b.getEncoding()); // Encoding should be the same
		
		// Tag must equal first byte of encoding
		assertEquals(a.getTag(),enc.byteAt(0));
		
		// convert to canonical for following
		a=a.getCanonical();
		if (a.isCompletelyEncoded()) {
			doCompleteEncodingTests(a);
		}
	}
	
	/**
	 * Encoding tests for an arbitrary cell
	 * @param a Any Cell, might not be CVM value
	 */
	private static void doAnyEncodingTests(ACell a) {
		Blob encoding = Format.encodedBlob(a);
		if (a==null) {
			assertSame(Blob.NULL_ENCODING,encoding);
			return;
		} 
		
		assertEquals(a.getTag(),encoding.byteAt(0)); // Correct Tag
		assertSame(encoding,a.getEncoding()); // should be same cached encoding
		assertEquals(encoding.count,a.getEncodingLength());
			
		if (a.isCVMValue()) {
			assertNotNull(a.getType());
		}

		// Any encoding should be less than or equal to the limit
		assertTrue(encoding.count <= Format.LIMIT_ENCODING_LENGTH);
		
		// If length exceeds MAX_EMBEDDED_LENGTH, cannot be an embedded value
		if (encoding.count > Format.MAX_EMBEDDED_LENGTH) {
			assertFalse(Format.isEmbedded(a),()->"Should not be embedded: "+Utils.getClassName(a)+ " = "+Utils.toString(a));
		}

		try {
			// Test that we can re-read the encoding accurately
			ACell a2 = Format.read(encoding);
			assertEquals(a, a2);
			
			// Encoding should be cached, probably but not necessarily identical
			assertEquals(a2.cachedEncoding(),encoding);
			
			// Test that we can re-read from a sliced Blob
			ABlob t=Samples.SMALL_BLOB.append(encoding);
			Blob offsetEncoding=t.slice(Samples.SMALL_BLOB.count()).toFlatBlob();
			ACell a3= Format.read(offsetEncoding);
			assertEquals(a, a3);
		} catch (BadFormatException e) {
			fail("Can't read encoding: 0x" + encoding.toHexString(), e);
			return;
		}
		
		assertThrows(BadFormatException.class,()->Format.read(encoding.append(Samples.SMALL_BLOB).toFlatBlob()));
	}
	

	/**
	 * Test Hash properties for an arbitrary cell
	 * @param a
	 */
	private static void doHashTests(ACell a) {
		Hash h=a.getHash();
		assertNotNull(h);
		assertSame(h,a.getHash());
		assertSame(h,a.getEncoding().getContentHash(),()->"Inconsistent Hash on "+a);
		assertEquals(h,a.getRef().getHash());

	}

	/**
	 * Test properties of a complete encoding.
	 * @param a
	 */
	private static void doCompleteEncodingTests(ACell a) {
		RefTreeStats stats=Refs.getRefTreeStats(a.getRef());
		assertTrue(stats.embedded==stats.total-(a.isEmbedded()?0:1));
	}

	/**
	 * Tests for canonical properties
	 * @param a
	 */
	private static void doCanonicalTests(ACell a) {
		Blob enc=a.getEncoding();
		if (a.isCanonical()) {
			// Canonical objects should map to themselves
			assertSame(a,a.getCanonical());
			assertSame(a,a.toCanonical());
			
			// tests for memory size and ref usage
			long memorySize=a.getMemorySize();
			long encodingSize=a.getEncodingLength();
			int rc=a.getRefCount();
			long childMem=0;
			for (int i=0; i<rc; i++) {
				Ref<ACell> childRef=a.getRef(i);
				long cms=childRef.getMemorySize();
				childMem+=cms;
			}
			boolean embedded=Format.isEmbedded(a);
			if (embedded) {
				assertEquals(memorySize,childMem);
			} else {
				assertEquals(memorySize,encodingSize+childMem+Constants.MEMORY_OVERHEAD);
			}
		} else {
			// non-canonical objects should convert to a canonical object
			ACell canon=a.getCanonical();
			assertNotSame(canon,a);
			assertTrue(canon.isCanonical());
			assertEquals(a,canon);
			assertEquals(enc,canon.getEncoding());
			assertTrue(a.getRef().getValue().isCanonical());
			
			doCanonicalTests(canon);
		}
		
		// Encoding of canonical object should be equal to initial value
		assertEquals(enc, a.getCanonical().getEncoding());
		
		// Canonical object itself should be cached
		assertSame(a.getCanonical(), a.getCanonical());
	}

	private static void doCellRefTests(ACell a) {

		Ref<ACell> cachedRef=a.cachedRef;
		Ref<ACell> ref=a.getRef();
		if (cachedRef!=null) assertSame(ref,cachedRef);
		
		assertTrue(ref.getValue().isCanonical());
		
		// Repeated getRef should return same object
		assertSame(ref,a.getRef());
		
		assertEquals(a.isEmbedded(),ref.isEmbedded());
		
		ACell c=a.getCanonical();
		if (c!=a) {
			// Non-canonical! But should have equal Refs
			assertEquals(a.getRef(),c.getRef());
		} else {
			// Canonical so these should work
			assertSame(a,a.updateRefs(rf->rf));
		}
		
		Ref<ACell> refD=ref.toDirect();
		assertTrue(ref.equals(refD));
		assertTrue(refD.equals(ref));
	}

	@SuppressWarnings("unused")
	private static void doCellStorageTest(ACell a) throws InvalidDataException, IOException {
		
		AStore temp=Stores.current();
		try {
			// test using a new memory store
			MemoryStore ms=new MemoryStore();
			Stores.setCurrent(ms);
			
			Ref<ACell> r=a.getRef();
			
			Hash hash=r.getHash();
			
			assertNull(ms.refForHash(hash));
			
			// persist the cell
			Cells.persist(a);
			
			// retrieve from store
			Ref<ACell> rr=ms.refForHash(hash);
			
			// should be able to retrieve and validate complete structure
			assertNotNull(rr,()->"Failed to retrieve from store with "+Utils.getClassName(a) + " = "+a);
			ACell b=rr.getValue();
			b.validate();
			assertEquals(a,b);
		} finally {
			Stores.setCurrent(temp);
		}
	}

	/**
	 * Tests for any value implementing the IRefContainer interface
	 * 
	 * @param a
	 */
	private static void doRefContainerTests(ACell a) {
		if (!a.isCanonical()) return;
		int rc=a.getRefCount();
	
		assertTrue(rc>=0);
		assertTrue(rc<=Format.MAX_REF_COUNT);
		assertEquals(rc,Cells.refCount(a));
		if (rc>0) {
			long tcount = Refs.totalRefCount(a);
			assertTrue(rc <= tcount);
		}
	}

	/**
	 * GEneric test for any two distinct objects that should be considered equal on the CVM
	 * @param a First instance
	 * @param b Second instance
	 */
	public static void doEqualityTests(ACell a, ACell b) {
		assertNotSame(a,b);
		assertTrue(a.equals(b));
		assertTrue(b.equals(a));
		assertEquals(a.hashCode(), b.hashCode());
		assertEquals(a.getHash(), b.getHash());
		assertEquals(a.getTag(),b.getTag());
		assertSame(a.getType(),b.getType());
		
		Blob enc=a.getEncoding();
		assertEquals(enc, b.getEncoding());
		try {
			assertEquals(a, Format.read(enc));
		} catch (BadFormatException e) {
			throw Utils.sneakyThrow(e);
		}
	}

}
