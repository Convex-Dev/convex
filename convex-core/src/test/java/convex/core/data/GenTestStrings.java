package convex.core.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.runner.RunWith;

import com.pholser.junit.quickcheck.From;
import com.pholser.junit.quickcheck.Property;
import com.pholser.junit.quickcheck.runner.JUnitQuickcheck;

import convex.core.lang.RT;
import convex.core.lang.Reader;
import convex.test.generators.StringGen;

@RunWith(JUnitQuickcheck.class)
public class GenTestStrings {
	@Property
	public void testStringProperties(@From(StringGen.class) AString a) {
		AString cvm=Strings.create(a.toString());
		if (cvm!=null) assertEquals(a,cvm);
		
		String printed=RT.print(a, 1000000).toString();
		assertEquals(a,Reader.read(printed));
	}
	
	@Property
	public void testJabaStringProperties(String a) {
		AString cvm=Strings.create(a);
		assertNotNull(cvm);
	}
}
