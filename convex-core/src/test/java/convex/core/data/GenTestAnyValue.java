package convex.core.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;

import org.junit.runner.RunWith;

import com.pholser.junit.quickcheck.From;
import com.pholser.junit.quickcheck.Property;
import com.pholser.junit.quickcheck.runner.JUnitQuickcheck;

import convex.core.exceptions.BadFormatException;
import convex.core.exceptions.InvalidDataException;
import convex.core.store.Stores;
import convex.core.util.Utils;
import convex.test.Samples;
import convex.test.generators.ValueGen;

@RunWith(JUnitQuickcheck.class)
public class GenTestAnyValue {
	
	@Property
	public void printFormats(@From(ValueGen.class) ACell o) {
		String s=Utils.print(o);
		assertNotNull(s);
		assertTrue(s.length()>0,"Printing type "+Utils.getClass(o));
		
		// TODO: handle all reader cases
		//Object o2=Reader.read(s);
		// if (o!=null) assertNotNull(o2); 
		
		
	}
	
	@Property
	public void genericTests(@From(ValueGen.class) ACell o) throws InvalidDataException, BadFormatException, IOException {
		ObjectsTest.doAnyValueTests(o);
	}
	
	@Property
	public void testUpdateRefs(@From(ValueGen.class) ACell o)  {
		if (o instanceof ACell) {
			ACell rc=(ACell) o;
			int n=rc.getRefCount();
			assertThrows(IndexOutOfBoundsException.class,()->rc.getRef(n));
			assertThrows(IndexOutOfBoundsException.class,()->rc.getRef(-1),()->"Invalid ref worked on "+Utils.getClassName(o));
			if (n>0 ) {
				assertNotNull(rc.getRef(0));
				assertEquals(rc,rc.updateRefs(r->r));
			}
		}
	}
	
	@Property
	public void testFuzzing(@From(ValueGen.class) ACell o) throws InvalidDataException  {
		Blob b=Format.encodedBlob(o);
		FuzzTestFormat.doMutationTest(b);
		
		if (o instanceof ACell) {
			// break all the refs! This should still pass validateCell(), since it woun't change structure.
			ACell c=((ACell)o).updateRefs(r->{ 
				byte[] badBytes=r.getHash().getBytes();
				Utils.writeInt(badBytes, 28,12255);
				Hash badHash=Hash.wrap(badBytes);
				return Ref.forHash(badHash);
			});
			c.validateCell();
		}
	}
	
	@Property
	public void validEmbedded(@From(ValueGen.class) ACell o) throws InvalidDataException, BadFormatException, IOException {
		if (Format.isEmbedded(o)) {
			Cells.persist(o); // NOTE: may have child refs to persist
			
			Blob data=Format.encodedBlob(o);
			ACell o2=Format.read(data);
			
			// check round trip properties
			assertEquals(o,o2);
			AArrayBlob data2=Format.encodedBlob(o2);
			assertEquals(data,data2);
			assertTrue(Format.isEmbedded(o2));
			
			// when we persist a ref to an embedded object, should be the object itself
			Ref<ACell> ref=Ref.get(o);
			assertEquals(data,ref.getEncoding()); // should encode ref same as value
		} else {
			// when we persist a ref to non-embedded object, should be a ref type
			Ref<?> ref=Ref.get(o);
			Blob b=ref.getEncoding();
			assertEquals(Tag.REF,b.byteAt(0));
			assertEquals(Ref.INDIRECT_ENCODING_LENGTH,b.count());
		}
	}
	
	@Property (trials=20)
	public void dataRoundTrip(@From(ValueGen.class) ACell o) throws BadFormatException, IOException {
		Blob data=Format.encodedBlob(o);
		
		// introduce a small offset to ensure blobs working correctly
		data=Samples.ONE_ZERO_BYTE_DATA.append(data).slice(1).toFlatBlob();
		
		// check persistence
		o=Cells.persist(o);
		Ref<ACell> dataRef=Ref.get(o); // ensure in store
		Hash hash=Hash.get(o);
		assertEquals(dataRef.getHash(),hash);
		
		// re-read data, should be canonical
		ACell o2=Format.read(data);
		assertTrue(Format.isCanonical(o2));
		
		// equality checks
		assertEquals(o,o2);
		if (o!=null) assertEquals(o.hashCode(),o2.hashCode());
		assertEquals(hash,Hash.get(o2));

		// re-encoding
		AArrayBlob data2=Format.encodedBlob(o2);
		assertEquals(data,data2);
		
		// simulate retrieval via hash
		Ref<ACell> dataRef2=Stores.current().refForHash(hash);
		if (dataRef2!=null) {
			// Have in store
			assertEquals(dataRef,dataRef2);
			Ref<ACell> r2=Ref.forHash(hash);
			ACell o3=r2.getValue();
			assertEquals(o,o3);
		}
	}
	
	@Property
	public void setInclusion(@From(ValueGen.class) ACell o) throws BadFormatException, InvalidDataException {
		ASet<ACell> s=Sets.of(o);
		s.validate();
		assertEquals(o,s.iterator().next());
		
		ASet<ACell> s2=s.exclude(o);
		assertTrue(s2.isEmpty());
	}

}
