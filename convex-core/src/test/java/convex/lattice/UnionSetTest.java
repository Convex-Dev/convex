package convex.lattice;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import convex.core.data.ASet;
import convex.core.data.Sets;
import convex.core.data.prim.CVMLong;
import convex.lattice.cursor.Root;

class UnionSetTest {
	@Test void independentDraftsConvergeAndRepeatedSyncIsInert() {
		var root=Root.<ASet<CVMLong>>create(null);
		var live=new UnionSet<>(root);
		var first=live.fork();
		var second=live.fork();
		assertTrue(first.isEmpty());
		assertNull(first.sync());
		assertNull(root.get(),"Reads and empty publication do not materialise a set");
		first.add(CVMLong.ONE);
		second.add(CVMLong.TWO);
		assertNull(root.get());
		first.sync();
		second.sync();
		first.sync();
		assertEquals(Sets.of(1,2),root.get());
		assertEquals(root.get(),first.get());
		assertEquals(root.get(),second.get());
		var before=root.get();
		first.sync();
		live.merge(before);
		assertSame(before,root.get());
	}

	@Test void deliveryIsAssociativeCommutativeAndIdempotent() {
		var a=Sets.<CVMLong>of(1,2);
		var b=Sets.<CVMLong>of(2,3);
		var c=Sets.<CVMLong>of(3,4);
		var left=new UnionSet<>(Root.create(a));
		var right=new UnionSet<>(Root.create(b));
		left.merge(b);
		right.merge(a);
		assertEquals(left.get(),right.get());
		left.merge(c);
		var bc=new UnionSet<>(Root.create(b));
		bc.merge(c);
		var grouped=new UnionSet<>(Root.create(a));
		grouped.merge(bc.get());
		assertEquals(left.get(),grouped.get());
		var before=left.get();
		left.merge(c);
		left.merge(null);
		assertSame(before,left.get());
		assertEquals(Sets.of(1,2,3,4),left.get());
	}
}
