package convex.observer;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.json.simple.JSONValue;
import org.junit.jupiter.api.Test;

import convex.core.data.ACell;
import convex.core.lang.RT;

public class ObserverTest {
	@Test public void testArrayJSON() {
		long [] ls=new long[] {1,2,3};
		ACell al=RT.cvm(ls);
		
		String s=JSONValue.toJSONString(al);
		assertEquals("[1,2,3]",s);
	}

}
