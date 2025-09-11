package convex.observer;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import convex.core.data.ACell;
import convex.core.data.AString;
import convex.core.lang.RT;
import convex.core.util.JSON;

public class ObserverTest {
	@Test public void testArrayJSON() {
		long [] ls=new long[] {1,2,3};
		ACell al=RT.cvm(ls);
		
		AString s=JSON.print(al);
		assertEquals("[1,2,3]",s.toString());
	}

}
