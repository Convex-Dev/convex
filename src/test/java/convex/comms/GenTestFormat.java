package convex.comms;

import static org.junit.Assert.assertEquals;

import org.junit.runner.RunWith;

import com.pholser.junit.quickcheck.From;
import com.pholser.junit.quickcheck.Property;
import com.pholser.junit.quickcheck.runner.JUnitQuickcheck;

import convex.core.data.ACell;
import convex.core.data.AString;
import convex.core.data.Blob;
import convex.core.data.Format;
import convex.core.data.FuzzTestFormat;
import convex.core.data.LongBlob;
import convex.core.data.Ref;
import convex.core.data.Strings;
import convex.core.exceptions.BadFormatException;
import convex.core.util.Utils;
import convex.test.generators.PrimitiveGen;
import convex.test.generators.ValueGen;

@RunWith(JUnitQuickcheck.class)
public class GenTestFormat {
	@Property
	public void messageRoundTrip(String str) throws BadFormatException {
		AString s=Strings.create(str);
		Blob b = Format.encodedBlob(s);
		AString s2 = Format.read(b);
		assertEquals(s, s2);
		assertEquals(b, Format.encodedBlob(s2));

		FuzzTestFormat.doMutationTest(b);
	}

	@Property
	public void primitiveRoundTrip(@From(PrimitiveGen.class) ACell prim) throws BadFormatException {
		Blob b = Format.encodedBlob(prim);
		ACell o = Format.read(b);
		assertEquals(prim, o);
		assertEquals(b, Format.encodedBlob(o));

		FuzzTestFormat.doMutationTest(b);
	}

	@Property
	public void dataRoundTrip(@From(ValueGen.class) ACell value) throws BadFormatException {
		Ref<ACell> pref = Ref.createPersisted(value); // ensure persisted
		Blob b = Format.encodedBlob(value);
		ACell o = Format.read(b);

		// TODO: think about this exception
		if (!(value instanceof LongBlob)) assertEquals(Utils.getClass(value), Utils.getClass(o));
		assertEquals(value, o);
		assertEquals(b, Format.encodedBlob(o));
		assertEquals(pref.getValue(), o);

		FuzzTestFormat.doMutationTest(b);
	}
}
