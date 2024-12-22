package convex.comms;

import static org.junit.Assert.assertEquals;

import java.io.IOException;

import org.junit.runner.RunWith;

import com.pholser.junit.quickcheck.From;
import com.pholser.junit.quickcheck.Property;
import com.pholser.junit.quickcheck.runner.JUnitQuickcheck;

import convex.core.data.ACell;
import convex.core.data.AString;
import convex.core.data.Blob;
import convex.core.data.Cells;
import convex.core.data.Format;
import convex.core.data.FuzzTestFormat;
import convex.core.data.Ref;
import convex.core.data.Strings;
import convex.core.exceptions.BadFormatException;
import convex.test.generators.PrimitiveGen;
import convex.test.generators.ValueGen;

@RunWith(JUnitQuickcheck.class)
public class GenTestFormat {
	@Property
	public void messageRoundTrip(String str) throws BadFormatException {
		AString s=Strings.create(str);
		Blob b = Cells.encode(s);
		AString s2 = Format.read(b);
		assertEquals(s, s2);
		assertEquals(b, Cells.encode(s2));

		FuzzTestFormat.doMutationTest(b);
	}

	@Property
	public void primitiveRoundTrip(@From(PrimitiveGen.class) ACell prim) throws BadFormatException, IOException {
		Blob b = Cells.encode(prim);
		if (!Cells.isEmbedded(prim)) {
			// persist in case large
			Cells.persist(prim);
		}
		ACell o = Format.read(b);
		assertEquals(prim, o);
		assertEquals(b, Cells.encode(o));

		FuzzTestFormat.doMutationTest(b);
	}

	@Property
	public void dataRoundTrip(@From(ValueGen.class) ACell value) throws BadFormatException, IOException {
		Ref<ACell> pref = Ref.get(Cells.persist(value)); // ensure persisted
		Blob b = Cells.encode(value);
		try {
			ACell o = Format.read(b);
			
			assertEquals(value, o);
			assertEquals(b, Cells.encode(o));
			assertEquals(pref.getValue(), o);

			FuzzTestFormat.doMutationTest(b);
		} catch (BadFormatException e) {
			System.err.println("Bad format in GenTestFromat: "+b);
			throw e;
		}


	}
}
