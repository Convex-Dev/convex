package convex.core.lang;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import static convex.test.Assertions.*;

import org.junit.runner.RunWith;

import com.pholser.junit.quickcheck.From;
import com.pholser.junit.quickcheck.Property;
import com.pholser.junit.quickcheck.generator.java.lang.LongGenerator;
import com.pholser.junit.quickcheck.runner.JUnitQuickcheck;

import convex.core.data.ACell;
import convex.core.data.ADataStructure;
import convex.core.data.AList;
import convex.core.data.ASequence;
import convex.core.data.ASet;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Address;
import convex.core.data.Blob;
import convex.core.data.BlobsTest;
import convex.core.data.Lists;
import convex.core.data.MapEntry;
import convex.core.data.Sets;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMBool;
import convex.core.data.prim.CVMByte;
import convex.core.data.prim.CVMLong;
import convex.core.util.Utils;
import convex.test.generators.AddressGen;
import convex.test.generators.ListGen;
import convex.test.generators.SetGen;
import convex.test.generators.VectorGen;

/**
 * Set of generative tests for Runtime functions
 * 
 * Generally grouped according to generated input types. The idea is to generate random sets of paramter
 * values and check that RT / core functions behave as expected.
 */
@RunWith(JUnitQuickcheck.class)
public class GenTestCore {
	
	private void doDataStructureTests(ADataStructure<ACell> a) {
		long n=RT.count(a);

		assertTrue(RT.bool(a));
		
		ASet<ACell> uniqueVals=RT.castSet(a);
		long ucount=RT.count(uniqueVals);
		assertTrue(ucount<=n);
		
		if (n>0) {
			assertTrue(ucount>0);
		} else {
			assertSame(a.empty(),a);
		}
	}

	private void doSequenceTests(ASequence<ACell> a)  {
		doDataStructureTests(a);
		
		long n=RT.count(a);
		
		assertSame(a,RT.sequence(a));
		
		// bounds exceptions
		assertThrows(IndexOutOfBoundsException.class,()->RT.nth(a,n));
		assertThrows(IndexOutOfBoundsException.class,()->RT.nth(a,-1));
	}
	
	/**
	 * Tests for objects that can be coerced into sequences
	 * @param a
	 */
	private void doSequenceableTests(ACell a) {
		ASequence<ACell> seq=RT.sequence(a);
		doSequenceTests(seq);
	}
	
	@SuppressWarnings({ "rawtypes", "unchecked" })
	@Property
	public void testListFunctions(@From(ListGen.class) AList a) {
		doSequenceTests(a);
		
		assertSame(Lists.empty(),a.empty());
		
		AString foos=Strings.create("foo");
		ASequence<ACell> ca=RT.cons(foos, a);
		assertEquals(foos,ca.get(0));
		// assertEquals(a,RT.next(ca)); // TODO BUG: broken for big lists / vectors
		assertEquals(ca,a.conj(foos));
	}
	
	@SuppressWarnings({ "rawtypes", "unchecked" })
	@Property
	public void testVectorFunctions(@From(VectorGen.class) AVector a) {
		doSequenceTests(a);
		
		if (!(a instanceof MapEntry)) {
			// only true for regular vectors
			assertSame(a,RT.vec(a)); 
		}
		assertSame(Vectors.empty(),a.empty());
	
		AString foos=Strings.create("foo");
		long n=RT.count(a);
		AVector<ACell> ca=a.conj(foos);
		assertEquals(foos,RT.nth(ca,n));
	}
	
	private void doAddressTests(Address a) {
		assertSame(a,RT.castAddress(a));
		long n=RT.count(a);
		
		Blob b=a.toBlob();
		assertEquals(b,RT.castBlob(a));
		assertEquals(a.toHexString(),b.toHexString());
		
		// Check a byte in the Address
		assertSame(CVMByte.create(a.byteAt(6)),RT.nth(a, 6));
		
		assertThrows(IndexOutOfBoundsException.class,()->RT.nth(a,-1));
		assertThrows(IndexOutOfBoundsException.class,()->RT.nth(a,n));
		
		BlobsTest.doBlobTests(a);
	}
	
	@Property
	public void testAddressFunctions(@From(AddressGen.class) Address a) {
		doAddressTests(a);
	}
	
	@SuppressWarnings({ "rawtypes", "unchecked" })
	@Property
	public void testSetFunctions(@From(SetGen.class) ASet a) {
		doSequenceableTests(a);
		
		assertSame(a,RT.castSet(a));
		assertSame(Sets.empty(),a.empty());
	
		assertEquals(a,RT.castSet(RT.sequence(a)));

		long n=RT.count(a);
		AString key=Strings.create("newkey1");
		// loop until we have a key not in set
		while(a.contains(key)) {
			key=Strings.create("newkey"+(Integer.parseInt(key.toString().substring(6))+1));
		}
		
		ASet<ACell> ca=a.conj(key);
		assertSame(CVMBool.TRUE,RT.get(ca, key));
		assertEquals(n+1, RT.count(ca));
		assertFalse(a.containsKey(key));
		assertTrue(ca.containsKey(key));
	}
	
	@Property 
	public void testLongFunctions(@From(LongGenerator.class) Long a) {
		CVMLong ca=CVMLong.create(a);
		
		long v=a;
		assertEquals(Long.toString(v),RT.str(ca).toString());
		assertSame(CVMByte.create(v),RT.castByte(ca));
		assertCVMEquals((char)v,RT.toCharacter(ca));
		assertCVMEquals(v+1,RT.inc(ca));
		assertCVMEquals(v-1,RT.dec(ca));
		assertCVMEquals(0,RT.compare(a,(Long)v));
		assertCVMEquals(-1,RT.compare((long)a,v+10));
		assertCVMEquals(1,RT.compare((long)a,v-10));
	
		CVMLong[] args=new CVMLong[] {ca};
		assertEquals(-v,RT.minus(args).longValue());
		assertEquals(v,RT.plus(args).longValue());
		assertEquals(v,RT.times(args).longValue());
		assertEquals(1.0/v,RT.divide(args).doubleValue());
		
		assertTrue(RT.lt(args));
		assertTrue(RT.gt(args));
		assertTrue(RT.le(args));
		assertTrue(RT.ge(args));
		assertTrue(RT.eq(args));
		
		assertTrue(Utils.bool(a)); // longs are always truthy
		
		assertNull(RT.count(a));
		assertNull(RT.vec(a));
	}
	
	@Property 
	public void testLongMaths(@From(LongGenerator.class) Long a, @From(LongGenerator.class) Long b) {
		assertEquals(RT.compare(a, b),-RT.compare(b,a));

		CVMLong ca=CVMLong.create(a);
		CVMLong cb=CVMLong.create(b);

		
		CVMLong[] args=new CVMLong[] {ca,cb};
		assertEquals(a+b,RT.plus(args).longValue());
		assertEquals(a*b,RT.times(args).longValue());
		assertEquals(a-b,RT.minus(args).longValue());
		assertEquals(((double)a)/((double)b),RT.divide(args).doubleValue());
		
		assertEquals(a<b,RT.lt(args));
		assertEquals(a>b,RT.gt(args));
		assertEquals(a<=b,RT.le(args));
		assertEquals(a>=b,RT.ge(args));
		assertEquals(a==b,RT.eq(args));
		// assertEquals(a!=b,RT.ne(args)); // TODO: do we need this?
	}
	
}
