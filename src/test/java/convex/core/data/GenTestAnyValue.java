package convex.core.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.runner.RunWith;

import com.pholser.junit.quickcheck.From;
import com.pholser.junit.quickcheck.Property;
import com.pholser.junit.quickcheck.runner.JUnitQuickcheck;

import convex.core.crypto.Hash;
import convex.core.exceptions.BadFormatException;
import convex.core.exceptions.InvalidDataException;
import convex.core.lang.RT;
import convex.core.store.Stores;
import convex.core.util.Utils;
import convex.generators.ValueGen;
import convex.test.Samples;

@RunWith(JUnitQuickcheck.class)
public class GenTestAnyValue {
	
	@Property
	public void stringFormats(@From(ValueGen.class) Object o) {
		String edn=Utils.ednString(o);
		assertNotNull(Utils.ednString(o));
		assertTrue(edn.length()>0);
	}
	
	@Property
	public void genericTests(@From(ValueGen.class) Object o) throws InvalidDataException, BadFormatException {
		ObjectsTest.doAnyValueTests(o);
	}
	
	@Property
	public void testUpdateRefs(@From(ValueGen.class) Object o)  {
		if (o instanceof IRefContainer) {
			IRefContainer rc=(IRefContainer) o;
			int n=rc.getRefCount();
			assertThrows(IndexOutOfBoundsException.class,()->rc.getRef(n));
			assertThrows(IndexOutOfBoundsException.class,()->rc.getRef(-1));
			if (n>0 ) {
				assertNotNull(rc.getRef(0));
				assertSame(rc,rc.updateRefs(r->r));
			}
		}
	}
	
	@Property
	public void testFuzzing(@From(ValueGen.class) Object o) throws InvalidDataException  {
		Blob b=Format.encodedBlob(o);
		FuzzTestFormat.doMutationTest(b);
		
		if (o instanceof IRefContainer) {
			// break all the refs! This should still pass validateCell(), since it woun't change structure.
			ACell c=((IRefContainer)o).updateRefs(r->{
				byte[] badBytes=r.getHash().getBytes();
				Utils.writeInt(12255, badBytes, 28);
				Hash badHash=Hash.wrap(badBytes);
				return Ref.forHash(badHash);
			});
			c.validateCell();
		}
	}
	
	@Property
	public void validEmbedded(@From(ValueGen.class) Object o) throws InvalidDataException, BadFormatException {
		if (Format.isEmbedded(o)) {
			// shouldn't need to persist embedded data
			Blob data=Format.encodedBlob(o);
			
			Object o2=Format.read(data);
			RT.validate(o2);
			
			// check round trip properties
			assertEquals(o,o2);
			AArrayBlob data2=Format.encodedBlob(o2);
			assertEquals(data,data2);
			assertTrue(Format.isEmbedded(o2));
			
			// when we persist a ref to an embedded object, should be the object itself
			Ref<Object> ref=Ref.create(o);
			assertTrue(ref.isDirect()); 
			assertEquals(data,Format.encodedBlob(ref)); // should encode ref same as value
		} else {
			// when we persist a ref to non-embedeed object, should be a ref type
			Ref<?> ref=Ref.create(o);
			Blob b=Format.encodedBlob(ref);
			assertEquals(Tag.REF,b.get(0));
			assertEquals(33,b.length());
		}
	}
	
	@Property (trials=20)
	public void dataRoundTrip(@From(ValueGen.class) Object o) throws BadFormatException {
		Blob data=Format.encodedBlob(o);
		
		// introduce a small offset to ensure blobs working correctly
		data=Samples.ONE_ZERO_BYTE_DATA.append(data).slice(1).toBlob();
		
		Ref<Object> dataRef=Ref.create(o).persist(); // ensure in store
		Hash hash=Hash.compute(o);
		assertEquals(dataRef.getHash(),hash);
		
		// re-read data, should be canonical
		Object o2=Format.read(data);
		assertTrue(Format.isCanonical(o2));
		
		// equality checks
		assertEquals(o,o2);
		if (o!=null) assertEquals(o.hashCode(),o2.hashCode());
		assertEquals(hash,Hash.compute(o2));

		// re-encoding
		AArrayBlob data2=Format.encodedBlob(o2);
		assertEquals(data,data2);
		
		// simulate retrieval via hash
		Ref<Object> dataRef2=Stores.current().refForHash(hash);
		if (dataRef2==null) {
			assertTrue(Format.isEmbedded(o));
		} else {
			// should be in store if not embedded
			assertFalse(Format.isEmbedded(o));
			assertEquals(dataRef,dataRef2);
			Ref<Object> r2=Ref.forHash(hash);
			Object o3=r2.getValue();
			assertEquals(o,o3);
		}
	}
	
	@Property
	public void setInclusion(@From(ValueGen.class) Object o) throws BadFormatException, InvalidDataException {
		ASet<Object> s=Sets.of(o);
		s.validate();
		assertEquals(o,s.iterator().next());
		
		ASet<Object> s2=s.exclude(o);
		assertTrue(s2.isEmpty());
	}

}
