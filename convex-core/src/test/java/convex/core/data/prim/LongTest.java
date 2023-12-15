package convex.core.data.prim;

import static convex.test.Assertions.assertCVMEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.util.HashSet;

import org.junit.jupiter.api.Test;

import convex.core.data.Blob;
import convex.core.data.Format;
import convex.core.data.ObjectsTest;
import convex.core.data.Strings;
import convex.core.exceptions.BadFormatException;
import convex.core.lang.NumericsTest;

public class LongTest {

	@Test
	public void testEquality() {
		long v=666666;
		ObjectsTest.doEqualityTests(CVMLong.create(v),CVMLong.create(v));
	}
	
	@Test 
	public void cacheTest() {
		assertSame(CVMLong.create(255),CVMLong.create(255));
		assertNotSame(CVMLong.create(666),CVMLong.create(666));
	}
	
	@Test public void testParseLong() {
		assertCVMEquals(1L, CVMLong.parse(1L));
		assertCVMEquals(0L, CVMLong.parse(CVMLong.ZERO));
		assertCVMEquals(13L, CVMLong.parse("13"));
		assertCVMEquals(-13L, CVMLong.parse(Strings.create("-13")));
		
		assertNull(CVMLong.parse("1.3"));
		assertNull(CVMLong.parse(":foo"));
		assertNull(CVMLong.parse(null));
		assertNull(CVMLong.parse(CVMBigInteger.MIN_POSITIVE));
	}

	@Test 
	public void testLongEncoding() {
		assertEquals("10",es(0));
		assertEquals("1101",es(1));
		assertEquals("11ff",es(-1));
		
		assertEquals("117f",es(127));
		assertEquals("120080",es(128));
		assertEquals("1200ff",es(255));
		assertEquals("12ff00",es(0xffffffffffffff00l));
		assertEquals("187fffffffffffffff",es(Long.MAX_VALUE));
		assertEquals("188000000000000000",es(Long.MIN_VALUE));
	}
	
	@Test 
	public void testBadEncoding() {
		// Excess leading zeros
		assertThrows(BadFormatException.class,()->Format.read("13000fff"));
		assertThrows(BadFormatException.class,()->Format.read("1100"));
		
		// Excess leading ones
		assertThrows(BadFormatException.class,()->Format.read("13ffffff"));
		assertThrows(BadFormatException.class,()->Format.read("14ff800000"));
		
		// Wrong lengths
		assertThrows(BadFormatException.class,()->Format.read("14ff"));
		assertThrows(BadFormatException.class,()->Format.read("12123456"));
	}
	
	@Test
	public void testUniqueEncodings() {
		HashSet<Blob> b=new HashSet<>();
		for (int i=-300; i<=300; i+=3) {
			CVMLong c=CVMLong.create(i);
			doLongTest(c);
			b.add(c.getEncoding());
		}
		assertEquals(201,b.size());
	}
	
	private String es(long v) {
		CVMLong c=CVMLong.create(v);
		doLongTest(c);
		return c.getEncoding().toHexString();
	}
	
	@Test public void testFromBlob() {
		assertEquals(0xff,Blob.fromHex("0000ff").longValue());
	}
	
	@Test public void testToBlob() {
		assertEquals(Blob.fromHex("00"),CVMLong.ZERO.toBlob());
		assertEquals(Blob.fromHex("ff"),CVMLong.MINUS_ONE.toBlob());
	}

	@Test public void testLongSamples() {
		doLongTest(CVMLong.ZERO);
		doLongTest(CVMLong.MIN_VALUE);
		doLongTest(CVMLong.MINUS_ONE);
		doLongTest(CVMLong.MAX_VALUE);
		doLongTest(CVMLong.create(666));
		doLongTest(CVMLong.create(Integer.MAX_VALUE));
		doLongTest(CVMLong.create(1L+Integer.MAX_VALUE));
		doLongTest(CVMLong.create(Integer.MIN_VALUE));
		doLongTest(CVMLong.create(Integer.MIN_VALUE-1L));
	}
	
	@Test public void testCompares() {
		assertEquals(0,CVMLong.ZERO.compareTo(CVMLong.ZERO));
		assertEquals(-1,CVMLong.ZERO.compareTo(CVMLong.ONE));
		assertEquals(1,CVMLong.ONE.compareTo(CVMLong.ZERO));
		
		assertTrue(CVMLong.MAX_VALUE.compareTo(CVMLong.ZERO)>0);
		assertTrue(CVMLong.MIN_VALUE.compareTo(CVMLong.ZERO)<0);
		
		assertEquals(0,CVMLong.ONE.compareTo(CVMLong.create(1)));
	}
	
	public void doLongTest(CVMLong a) {
		long val=a.longValue();
		long n=a.byteLength();
		
		assertEquals(CVMLong.create(val),a);
		assertTrue(a.isCanonical());
		assertTrue(a.isEmbedded());
		assertTrue(n<=8);
		assertEquals(val,a.longValue());
		assertEquals(val,a.toBlob().longValue());
		
		if (val!=0) {
			assertEquals(BigInteger.valueOf(val).toByteArray().length,a.byteLength());
			assertNotEquals(0,a.signum().longValue());
		}
		
		NumericsTest.doIntegerTests(a);
	}
}
