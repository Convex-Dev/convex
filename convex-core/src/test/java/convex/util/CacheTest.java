package convex.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

import convex.core.util.SoftCache;

public class CacheTest {
	@Test
	public void testCache() {
		SoftCache<String,Object> c=new SoftCache<>();
		
		String s="Hello";
		String w="World";
		c.put(s, w);
		assertEquals(w,c.get(s));
		
		assertNull(c.get(w));

		// Overwrite with null
		c.put(s, null);
		assertNull(c.get(s));
		
	}
	
	@Test
	public void testClear() {
		SoftCache<String,Object> c=new SoftCache<>();
		
		String s="Hello";
		String w="World";
		c.put(s, w);
		assertEquals(w,c.get(s));
		
		c.clear();
		assertNull(c.get(s));
	}

	// test that we don't blow up with OOM....
	public static void main(String... args) {
		long start=System.currentTimeMillis();
		SoftCache<Integer,Object> c=new SoftCache<>();
		for (int i=0; i<1000000; i++) {
			Object v=new int[100000];
			c.put(i, v);
		}
		System.out.println("Execution time: "+(System.currentTimeMillis()-start));
	}
}


