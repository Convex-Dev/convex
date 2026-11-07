package convex.lattice.cursor;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BinaryOperator;
import java.util.function.UnaryOperator;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import convex.core.data.ACell;
import convex.core.data.AHashMap;
import convex.core.data.AString;
import convex.core.data.Keyword;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import convex.lattice.JSONValueComponent;
import convex.lattice.ValueComponent;

class ReadOnlyCursorTest {

	private static final Keyword A=Keyword.intern("a");
	private static final Keyword B=Keyword.intern("b");
	private static final Keyword C=Keyword.intern("c");

	private static void assertRefused(Executable write) {
		assertThrows(ReadOnlyCursorException.class,write);
	}

	/** A root counting every write that reaches it. */
	private static final class CountingRoot<V extends ACell> extends Root<V> {
		final AtomicInteger writes=new AtomicInteger();
		CountingRoot(V value) { super(value); }
		@Override public void set(V value) { writes.incrementAndGet(); super.set(value); }
		@Override public V getAndSet(V value) { writes.incrementAndGet(); return super.getAndSet(value); }
		@Override public boolean compareAndSet(V expected,V value) { writes.incrementAndGet(); return super.compareAndSet(expected,value); }
		@Override public V getAndUpdate(UnaryOperator<V> f) { writes.incrementAndGet(); return super.getAndUpdate(f); }
		@Override public V updateAndGet(UnaryOperator<V> f) { writes.incrementAndGet(); return super.updateAndGet(f); }
		@Override public V getAndAccumulate(V x,BinaryOperator<V> f) { writes.incrementAndGet(); return super.getAndAccumulate(x,f); }
		@Override public V accumulateAndGet(V x,BinaryOperator<V> f) { writes.incrementAndGet(); return super.accumulateAndGet(x,f); }
	}

	@Test void readsPassThroughUnderTheStrictContract() {
		var root=new Root<AHashMap<Keyword,ACell>>(Maps.of(A,CVMLong.ONE,B,null));
		var view=root.readOnly();
		assertSame(view,view.readOnly());
		assertEquals(root.get(),view.get());
		assertEquals(CVMLong.ONE,view.get(A));
		assertTrue(view.path(B).exists());
		assertNull(view.path(B).get());
		assertFalse(view.path(C).exists());
		assertThrows(MissingValueException.class,() -> view.path(C).get());
		assertEquals(CVMLong.TWO,view.getOrDefault(CVMLong.TWO,C));
		assertEquals(Maps.empty(),view.path(C).withDefault(Maps.empty()).get());
		root.set(Maps.of(A,CVMLong.TWO));
		assertEquals(CVMLong.TWO,view.get(A));
	}

	@Test void everyWriteIsRefusedBeforeTheSourceIsTouched() {
		var root=new CountingRoot<AHashMap<Keyword,ACell>>(Maps.of(A,CVMLong.ONE));
		var view=root.readOnly();
		var value=root.get();
		assertRefused(() -> view.set(value));
		assertRefused(() -> view.set(Maps.empty()));
		assertRefused(() -> view.getAndSet(value));
		assertRefused(() -> view.compareAndSet(value,Maps.empty()));
		assertRefused(() -> view.update(v -> v));
		assertRefused(() -> view.getAndUpdate(v -> v));
		assertRefused(() -> view.updateAndGet(v -> v));
		assertRefused(() -> view.getAndAccumulate(value,(own,x) -> own));
		assertRefused(() -> view.accumulateAndGet(value,(own,x) -> own));
		assertRefused(() -> view.assoc(A,CVMLong.TWO));
		assertRefused(() -> view.assocIn(CVMLong.TWO,A));
		assertRefused(() -> view.persist(v -> v));
		assertRefused(() -> view.path(A).set(CVMLong.ONE));
		assertRefused(() -> view.path(A).set(CVMLong.TWO));
		assertRefused(() -> view.withDefault(Maps.empty()).path(B).set(CVMLong.TWO));
		assertRefused(() -> Transactions.execute(view,draft -> draft.path(A).set(CVMLong.TWO)));
		assertSame(value,root.get());
		assertEquals(0,root.writes.get());
	}

	@Test void forksAreWritableButTheirSyncIsRefused() {
		var root=new Root<AHashMap<Keyword,ACell>>(Maps.of(A,CVMLong.ONE));
		var fork=root.readOnly().fork();
		fork.path(A).set(CVMLong.TWO);
		assertEquals(CVMLong.TWO,fork.get(A));
		assertRefused(() -> fork.sync((own,other) -> own));
		assertEquals(CVMLong.TWO,fork.get(A));
		assertEquals(CVMLong.ONE,root.get(A));
	}

	/** The view can publish nothing, so syncing it fails; views nested under it sync by reading. */
	@Test void syncOfTheViewIsRefusedButNestedViewsSyncByReading() {
		var root=new CountingRoot<AHashMap<Keyword,ACell>>(Maps.of(A,CVMLong.ONE));
		var view=root.readOnly();
		var merges=new AtomicInteger();
		BinaryOperator<AHashMap<Keyword,ACell>> merge=(own,other) -> { merges.incrementAndGet(); return own; };
		assertRefused(() -> view.sync(merge));
		assertRefused(() -> view.<AHashMap<Keyword,ACell>>path().sync(merge)); // an empty path is the view itself
		assertEquals(CVMLong.ONE,view.path(A).sync((own,other) -> own));
		assertEquals(root.get(),view.withDefault(Maps.empty()).sync((own,other) -> own));
		assertEquals(0,merges.get());
		assertEquals(0,root.writes.get());
	}

	@Test void valueComponentsOverTheViewAreReadOnlyThroughout() {
		AString a=Strings.create("a");
		AString zero=Strings.create("0");
		var root=new Root<ACell>(Maps.of(a,Vectors.of(CVMLong.ONE)));
		var json=new JSONValueComponent<>(root.readOnly()).resolve(a,zero);
		assertEquals(CVMLong.ONE,json.value());
		assertRefused(() -> json.cursor().set(CVMLong.TWO));
		var component=new ValueComponent<>(root.readOnly());
		var literal=component.resolve(a);
		assertRefused(() -> literal.cursor().set(Vectors.empty()));
		assertRefused(component::sync);
		assertEquals(Vectors.of(CVMLong.ONE),literal.sync());
		assertEquals(Vectors.of(CVMLong.ONE),RT.getIn(root.get(),a));
	}

	@Test void viewsRefuseWithTheSameException() {
		var transformed=new Transformer<CVMLong,CVMLong>(new Root<>(CVMLong.ONE),v -> v);
		assertRefused(() -> transformed.set(CVMLong.TWO));
		assertInstanceOf(UnsupportedOperationException.class,new ReadOnlyCursorException());
	}
}
