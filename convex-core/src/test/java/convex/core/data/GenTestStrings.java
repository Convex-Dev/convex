package convex.core.data;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.runner.RunWith;

import com.pholser.junit.quickcheck.From;
import com.pholser.junit.quickcheck.Property;
import com.pholser.junit.quickcheck.runner.JUnitQuickcheck;

import convex.test.generators.StringGen;

@RunWith(JUnitQuickcheck.class)
public class GenTestStrings {
	@Property
	public void testStringProperties(@From(StringGen.class) AString a) {
		AString cvm=Strings.create(a.toString());
		if (cvm!=null) assertEquals(a,cvm);
	}
}
