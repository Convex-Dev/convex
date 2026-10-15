package convex.lattice.cursor;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import convex.core.data.prim.CVMLong;
import convex.lattice.ALatticeComponent;
import convex.lattice.LatticeContext;

/** Policy is resolved explicitly by components and never transported by cursors. */
public class ContextInheritanceTest {
	private static class Component extends ALatticeComponent<CVMLong> {
		Component(AForkableCursor<CVMLong> cursor) { super(cursor); }
		Component view() { return configured(new Component(cursor)); }
	}
	@Test public void childrenResolvePolicyWithoutLiveInheritance() {
		var parent=new Component(Root.create(CVMLong.ZERO));
		var first=LatticeContext.create(CVMLong.ONE,null);
		parent.setContext(first);
		var child=parent.view();
		parent.setContext(LatticeContext.create(CVMLong.TWO,null));
		assertSame(first,child.getContext());
		assertSame(parent.getContext(),parent.view().getContext());
		child.cursor().set(CVMLong.TWO);
		assertEquals(CVMLong.TWO,parent.cursor().get());
	}
	@Test public void cursorsExposeNoAmbientPolicy() {
		for (var type:new Class<?>[] {ACursor.class,Root.class,PathCursor.class,SignedCursor.class,StampedCursor.class}) {
			for (var field:type.getDeclaredFields()) assertNotEquals(LatticeContext.class,field.getType());
			for (var method:type.getMethods()) {
				assertNotEquals("getContext",method.getName());
				assertNotEquals("setContext",method.getName());
			}
		}
	}
}
