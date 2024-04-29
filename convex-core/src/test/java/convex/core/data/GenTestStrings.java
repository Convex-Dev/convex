package convex.core.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.runner.RunWith;

import com.pholser.junit.quickcheck.From;
import com.pholser.junit.quickcheck.Property;
import com.pholser.junit.quickcheck.runner.JUnitQuickcheck;

import convex.core.data.prim.CVMChar;
import convex.core.lang.RT;
import convex.core.lang.Reader;
import convex.test.generators.CharGen;
import convex.test.generators.StringGen;

@RunWith(JUnitQuickcheck.class)
public class GenTestStrings {
	@Property
	public void testStringProperties(@From(StringGen.class) AString a) {
		AString cvm=Strings.create(a.toString());
		
		// TODO: this might fail for some invalid UTF-8?
		if (cvm!=null) {
			assertEquals(a,cvm);
		}
		
		String printed=RT.print(a, 1000000).toString();
		assertEquals(a,Reader.read(printed));
	}
	
	@Property
	public void testJavaStringProperties(String a) {
		// Any valid Java string should become a CVM String
		AString cvm=Strings.create(a);
		assertNotNull(cvm);
	}
	
	@Property
	public void testCharProperties(@From(CharGen.class) CVMChar c) {
		AString s=Strings.create(c);
		assertNotNull(s);
		
		assertEquals(c,s.get(0));
		assertEquals(CVMChar.utfLength(c.longValue()),s.count());
	}
}
