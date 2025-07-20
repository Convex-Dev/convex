package convex.lattice;

import static convex.test.Assertions.assertCVMEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import convex.core.cvm.Keywords;
import convex.core.cvm.Symbols;
import convex.core.data.AIndex;
import convex.core.data.AMap;
import convex.core.data.AVector;
import convex.core.data.Index;
import convex.core.data.Keyword;
import convex.core.data.Maps;
import convex.core.data.Vectors;
import convex.core.data.prim.AInteger;
import convex.core.data.prim.CVMLong;

public class CursorTest {

	@Test public void testRoot() {
		Root<AInteger> root=new Root<>();
		doIntCursorTest(root);
	}
	
	@Test public void testPathCursor() {
		Root<AInteger> root=new Root<>();
		PathCursor<AInteger> pc=new PathCursor<AInteger>(root,Symbols.FOO);
		doIntCursorTest(pc);
	}
	
	@Test public void testVectorPathCursor() {
		Root<AInteger> root=new Root<>();
		PathCursor<AVector<AInteger>> vpc=new PathCursor<>(root,Symbols.BAR);
		vpc.set(Vectors.of(567,null,78));
		PathCursor<AInteger> pc=new PathCursor<>(vpc,CVMLong.ONE);
		doIntCursorTest(pc);
	}
	
	@Test public void testIndexPathCursor() {
		Root<AInteger> root=new Root<>();
		PathCursor<AIndex<Keyword,AInteger>> vpc=new PathCursor<>(root,Keywords.FOO);
		vpc.set(Index.of(Keywords.FOO,null,Keywords.BAR,3));
		PathCursor<AInteger> pc=new PathCursor<>(vpc,Keywords.FOO);
		doIntCursorTest(pc);
	}
	
	@Test public void testMapPathCursor() {
		Root<AInteger> root=new Root<>();
		PathCursor<AMap<Keyword,AInteger>> vpc=new PathCursor<>(root,Keywords.FOO);
		vpc.set(Maps.of(Keywords.FOO,null,Keywords.BAR,3));
		PathCursor<AInteger> pc=new PathCursor<>(vpc,Keywords.FOO);
		doIntCursorTest(pc);
	}
	
	private void doIntCursorTest(ACursor<AInteger> root) {
		assertEquals("nil",root.toString());

		CVMLong TWO=CVMLong.create(2); // just another value for testing
		
		root.set(1); // value is now 1
		assertCVMEquals(1,root.get());
		
		assertFalse(root.compareAndSet(CVMLong.ZERO, CVMLong.ZERO));
		assertTrue(root.compareAndSet(CVMLong.ONE, CVMLong.ZERO)); // value is now 0
		assertCVMEquals(0,root.get());
		
		assertCVMEquals(0,root.getAndSet(TWO)); // value is now 2
		assertSame(TWO,root.get());
		
		assertEquals(TWO,root.getAndUpdate(a->a.inc())); // value is now 3
		assertCVMEquals(4,root.updateAndGet(a->a.inc())); // value is now 4;
		
		assertCVMEquals(4,root.getAndAccumulate(CVMLong.ONE,(a,b)->a.add(b))); // value is now 5;
		assertCVMEquals(7,root.accumulateAndGet(TWO,(a,b)->a.add(b))); // value is now 7;

		assertEquals("7",root.toString());
	}

}
