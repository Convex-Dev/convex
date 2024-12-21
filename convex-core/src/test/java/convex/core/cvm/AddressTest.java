package convex.core.cvm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;

import org.junit.jupiter.api.Test;

import convex.core.data.ACell;
import convex.core.data.Blob;
import convex.core.data.Blobs;
import convex.core.data.BlobsTest;
import convex.core.data.Cells;
import convex.core.data.Format;
import convex.core.data.Hash;
import convex.core.data.ObjectsTest;
import convex.core.data.Ref;
import convex.core.exceptions.BadFormatException;
import convex.core.util.Utils;

public class AddressTest {

	@Test
	public void testAddress1() {
		Address a1=Address.create(1);
		assertEquals("#1",a1.toString());
		String hex="0000000000000001";
		assertEquals(hex,a1.toHexString());
		
		assertEquals(a1,Address.fromHex(hex));
		assertTrue(a1.compareTo(Blob.fromHex(hex))==0);
	}
	
	@Test
	public void testAddress2() {
		Address a1=Address.create(13);
		assertEquals("#13",a1.toString());
	}
	
	@Test
	public void testEncoding() throws BadFormatException {
		Address a= Address.create(17);
		Blob enc=a.getEncoding();
		assertEquals(Utils.toHexString(CVMTag.ADDRESS)+"11",enc.toHexString());
		ACell ra=Format.read(enc);
		assertTrue(ra instanceof Address);
		assertEquals(a,ra);
	}
	
	@Test
	public void testParse() {
		assertEquals("#1",Address.parse("#1").toString());
		assertEquals("#2",Address.parse("2").toString());
		assertEquals("#16",Address.parse("0x0000000000000010").toString());
	}
	
	@Test
	public void doHashTest() {
		Address a=Address.ZERO;
		Hash h=Hash.get(a);
		
		try {
			a=Cells.persist(a);
		} catch (IOException e) {
			fail(e);
		}
		Ref<ACell> r = Ref.get(a);
		assertSame(a, r.getValue()); // shouldn't get GC'd because we have a strong reference
		assertEquals(h,r.getHash());

	}
	
	@Test
	public void testCanonical() {
		Address a=Address.create(17);
		assertTrue(a.isCanonical());
		
		assertNull(Address.create(-1));
	}
	
	@Test
	public void testBlobBehaviour() {
		assertEquals(0L,Address.ZERO.longValue());
		assertEquals(Blobs.createFilled(0, 8),Address.ZERO.toFlatBlob());
		
		Address a1=Address.create(0x12345678);
		Address a2=Address.create(0x1234abcd);
		assertEquals(3,a1.hexMatch(a2, 8, 3));
		assertEquals(1,a1.hexMatch(a2, 11, 3));
		assertEquals(0,a1.hexMatch(a2, 13, 3));
	
		BlobsTest.doBlobLikeTests(Address.ZERO);
		BlobsTest.doBlobLikeTests(a1);
		BlobsTest.doBlobLikeTests(Address.MAX_VALUE);
	}
	
	@Test
	public void testGenericBehaviour() {
		doAddressTest(Address.ZERO);
		doAddressTest(Address.create(476476467));
		doAddressTest(Address.create(Long.MAX_VALUE));
	}

	private void doAddressTest(Address a) {
		ObjectsTest.doAnyValueTests(a);
	}
}
