package convex.core.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import org.junit.runner.RunWith;

import com.pholser.junit.quickcheck.From;
import com.pholser.junit.quickcheck.Property;
import com.pholser.junit.quickcheck.runner.JUnitQuickcheck;

import convex.core.data.prim.CVMChar;
import convex.core.lang.RT;
import convex.core.lang.Reader;
import convex.test.Samples;
import convex.test.generators.CharGen;
import convex.test.generators.StringGen;

@RunWith(JUnitQuickcheck.class)
public class StringsPropertyTest {
	@Property
	public void testStringProperties(@From(StringGen.class) AString a) {
		assertEquals(a,Strings.create(a.toBlob()),"Raw bytes must round trip, including malformed UTF-8");
		byte[] bytes=a.toBlob().getBytes();
		String javaString=new String(bytes,StandardCharsets.UTF_8);
		assertEquals(javaString,a.toString(),"Java conversion must match UTF-8 decoding");
		AString roundTrip=Strings.create(a.toString());
		assertNotNull(roundTrip);
		
		assertEquals(-1,a.charAt(a.count()));
		
		AString printed=RT.print(a, 100000);
		assertNotNull(printed);
		assertEquals(printed,RT.print(a, 100000));
		// Only well-formed UTF-8 survives Java text conversion byte-for-byte.
		// Malformed strings still exercise the raw-byte, decoding and printing checks above.
		if (Arrays.equals(bytes,javaString.getBytes(StandardCharsets.UTF_8))) {
			assertEquals(a,roundTrip);
			assertEquals(a,Reader.read(printed.toString()));
		}
	}

	@org.junit.Test
	public void testStringRoundTripExamples() {
		for (AString a:new AString[] {Strings.EMPTY,Strings.create("\"\n\r\t\\"),
				Samples.RUSSIAN_STRING,Samples.MIN_TREE_STRING,Strings.fromHex("ff"),
				Strings.fromHex("eda080"),Strings.fromHex("f09080")}) {
			testStringProperties(a);
		}
	}
	
	@Property
	public void testJavaStringProperties(String a) {
		// Any valid Java string should become a CVM String
		AString cvm=Strings.create(a);
		assertNotNull(cvm);
		// Java strings may contain unpaired surrogates, which UTF-8 encoding replaces.
		assertEquals(new String(a.getBytes(StandardCharsets.UTF_8),StandardCharsets.UTF_8),cvm.toString());
	}
	
	@Property
	public void testCharProperties(@From(CharGen.class) CVMChar c) {
		AString s=Strings.create(c);
		assertNotNull(s);
		
		assertEquals(c,s.get(0));
		assertEquals(CVMChar.utfLength(c.longValue()),s.count());
	}
}
