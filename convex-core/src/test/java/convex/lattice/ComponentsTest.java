package convex.lattice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.function.Consumer;

import convex.core.data.ACell;
import convex.core.data.CAD3Encoder;
import convex.core.data.Format;
import convex.core.exceptions.BadFormatException;
import convex.lattice.cursor.AForkableCursor;
import convex.lattice.cursor.Cursors;
import convex.lattice.cursor.ForkedCursor;
import convex.lattice.cursor.Root;

/**
 * Generic checks of the merge and reconcile rules every lattice component keeps,
 * given sample deliveries, as {@code ObjectsTest} checks every cell. Each check binds
 * the component to cursors made here, through {@link ALatticeComponent#rebind}, so it
 * can read both the destination and a fork and see which objects they hold.
 *
 * <p>Held cells are kept wherever a result equals them, so that held state converges
 * on stored cells rather than on equal copies: where these checks expect the held
 * object itself, an equal value is not enough. A root keeps its object when an update
 * gives an equal value, and a fork where at most one side changed publishes without
 * calling the component's reconcile, so for a component bound to a root those checks
 * hold whatever the component builds. The component's own rules are exercised by the
 * values it merges, the deliveries it refuses and the changes on both sides. Objects
 * nested within a value are not checked.</p>
 */
public class ComponentsTest {

	/**
	 * Checks for any two valid deliveries, including two that tie: an empty component
	 * takes a delivery as it is; an absent, repeated or re-decoded delivery changes
	 * nothing and keeps the held object; and a fork publishes by the reconcile rule,
	 * where a change on both sides gives the merge in one order or a conflict that
	 * changes neither side.
	 *
	 * @param component Component configured for the deliveries; rebound, never changed
	 * @param a First valid delivery
	 * @param b Second valid delivery
	 */
	public static <V extends ACell> void doMergeTests(ALatticeComponent<V> component, V a, V b) {
		for (V v:List.of(a,b)) {
			V held=merged(component,v);
			assertEquals(v,held);
			assertSame(held,mergeInto(component,held,null));
			assertSame(held,mergeInto(component,held,v));
			assertSame(held,mergeInto(component,held,copy(v)));
		}
		V ab=merged(component,a,b);
		assertSame(ab,mergeInto(component,ab,a));
		assertSame(ab,mergeInto(component,ab,b));
		assertSame(ab,mergeInto(component,ab,copy(ab)));

		V va=merged(component,a);

		// Neither side changed: the destination keeps its object
		Root<V> root=Cursors.root(va);
		ForkedCursor<V> fork=root.fork();
		assertSame(va,component.rebind(fork).sync());
		assertSame(va,root.get());

		// Only the destination changed: the fork takes the destination's object
		root=Cursors.root(va);
		fork=root.fork();
		ALatticeComponent<V> working=component.rebind(fork);
		component.rebind(root).merge(b);
		V published=root.get();
		working.sync();
		assertSame(published,root.get());
		assertSame(published,fork.get());

		// Only the fork changed: the destination takes the fork's value
		root=Cursors.root(va);
		fork=root.fork();
		working=component.rebind(fork);
		working.merge(b);
		working.sync();
		assertEquals(ab,root.get());

		// Both changed: the merge in one order, or a conflict
		root=Cursors.root(null);
		fork=root.fork();
		working=component.rebind(fork);
		working.merge(a);
		component.rebind(root).merge(b);
		V result=syncOrConflict(working,fork,root);
		if (result!=null) assertTrue(result.equals(ab)||result.equals(merged(component,b,a)),"Reconciled value must be a merge of the two");
	}

	/**
	 * Checks for two valid deliveries whose merge does not depend on order, in
	 * addition to {@link #doMergeTests}: merging in either order or in any grouping
	 * gives the same value, a fork whose change the destination does not hold
	 * publishes that value, and one whose change it already holds leaves the
	 * destination's object as it is. A component may still refuse a change on both
	 * sides with a conflict.
	 *
	 * @param component Component configured for the deliveries; rebound, never changed
	 * @param a First valid delivery
	 * @param b Second valid delivery
	 */
	public static <V extends ACell> void doJoinTests(ALatticeComponent<V> component, V a, V b) {
		doMergeTests(component,a,b);
		V ab=merged(component,a,b);
		assertEquals(ab,merged(component,b,a));
		assertEquals(ab,merged(component,ab));
		assertEquals(ab,mergeInto(component,merged(component,a),ab));
		assertEquals(ab,mergeInto(component,merged(component,b),ab));

		// Both changed: the join
		Root<V> root=Cursors.root(null);
		ForkedCursor<V> fork=root.fork();
		ALatticeComponent<V> working=component.rebind(fork);
		working.merge(a);
		component.rebind(root).merge(b);
		V result=syncOrConflict(working,fork,root);
		if (result!=null) assertEquals(ab,result);

		// Both changed, the destination already holding the fork's change: nothing new is published
		root=Cursors.root(null);
		fork=root.fork();
		working=component.rebind(fork);
		working.merge(a);
		ALatticeComponent<V> destination=component.rebind(root);
		destination.merge(a);
		destination.merge(b);
		V held=root.get();
		result=syncOrConflict(working,fork,root);
		if (result!=null) assertSame(held,result);
	}

	/**
	 * Checks that each refused delivery leaves a held value as it is, as the same
	 * object. The held value must already have any container that a refused delivery
	 * brings with it, since a component may keep a delivered container whose contents
	 * it refuses.
	 *
	 * @param component Component configured for the deliveries; rebound, never changed
	 * @param held Valid held value
	 * @param refused Deliveries the component refuses
	 */
	@SafeVarargs
	public static <V extends ACell> void doRefusalTests(ALatticeComponent<V> component, V held, V... refused) {
		for (V r:refused) assertSame(held,mergeInto(component,held,r));
	}

	/**
	 * Checks a component through its domain operations, as for one with no merge: two
	 * edits, each of which changes the value, applied to a fork and to its destination.
	 * A fork where neither changed leaves the destination's object as it is; where only
	 * the destination changed, the fork takes the destination's object; where only the
	 * fork changed, the destination takes the fork's value; and where both changed, sync
	 * gives the value of applying the two edits one after the other, in either order, or
	 * fails with a conflict that changes neither side. Each edit must give the same value
	 * whenever it is applied to the same value, so any clock it reads must be fixed.
	 *
	 * @param component Component configured for the edits; rebound, never changed
	 * @param start Value both sides start from
	 * @param a First edit
	 * @param b Second edit
	 */
	public static <V extends ACell,C extends ALatticeComponent<V>> void doEditTests(C component, V start,
			Consumer<? super C> a, Consumer<? super C> b) {
		V va=edited(component,start,a);
		V vb=edited(component,start,b);
		assertEquals(va,edited(component,start,a),"An edit must give the same value each time");
		assertEquals(vb,edited(component,start,b),"An edit must give the same value each time");
		assertNotEquals(start,va,"Each edit must change the value");
		assertNotEquals(start,vb,"Each edit must change the value");

		// Neither side changed: the destination keeps its object
		Root<V> root=Cursors.root(start);
		ForkedCursor<V> fork=root.fork();
		rebound(component,fork).sync();
		assertSame(start,root.get());

		// Only the destination changed: the fork takes the destination's object
		root=Cursors.root(start);
		fork=root.fork();
		C working=rebound(component,fork);
		b.accept(rebound(component,root));
		V published=root.get();
		working.sync();
		assertSame(published,root.get());
		assertSame(published,fork.get());

		// Only the fork changed: the destination takes the fork's value
		root=Cursors.root(start);
		fork=root.fork();
		working=rebound(component,fork);
		a.accept(working);
		working.sync();
		assertEquals(va,root.get());

		// Both changed: the two edits in one order, or a conflict
		root=Cursors.root(start);
		fork=root.fork();
		working=rebound(component,fork);
		a.accept(working);
		b.accept(rebound(component,root));
		V result=syncOrConflict(working,fork,root);
		if (result!=null) {
			assertTrue(result.equals(edited(component,start,a,b))||result.equals(edited(component,start,b,a)),
				"Reconciled value must be the two edits applied in one order");
		}
	}

	/** The value a component holding a start value holds after the edits, applied in order to one instance. */
	@SafeVarargs
	static <V extends ACell,C extends ALatticeComponent<V>> V edited(C component, V start, Consumer<? super C>... edits) {
		Root<V> root=Cursors.root(start);
		C target=rebound(component,root);
		for (Consumer<? super C> edit:edits) edit.accept(target);
		return root.get();
	}

	/** The component's counterpart on a cursor; a component's rebind returns its own class. */
	@SuppressWarnings("unchecked")
	private static <V extends ACell,C extends ALatticeComponent<V>> C rebound(C component, AForkableCursor<V> cursor) {
		return (C)component.rebind(cursor);
	}

	/** Merges a delivery into a component holding a value, returning the value it then holds. */
	static <V extends ACell> V mergeInto(ALatticeComponent<V> component, V held, V delivery) {
		Root<V> root=Cursors.root(held);
		V result=component.rebind(root).merge(delivery);
		assertSame(root.get(),result,"Merge must return the value held");
		return result;
	}

	/** The value an empty component holds after merging the deliveries in order. */
	@SafeVarargs
	static <V extends ACell> V merged(ALatticeComponent<V> component, V... deliveries) {
		V held=null;
		for (V d:deliveries) held=mergeInto(component,held,d);
		return held;
	}

	/**
	 * Syncs a fork whose working value and destination both changed. Returns the
	 * published value, or null after checking that a conflict changed neither side.
	 */
	private static <V extends ACell> V syncOrConflict(ALatticeComponent<V> working, ForkedCursor<V> fork, Root<V> root) {
		V own=fork.get();
		V other=root.get();
		try {
			V published=working.sync();
			assertSame(published,root.get());
			return published;
		} catch (MergeConflictException e) {
			assertSame(own,fork.get());
			assertSame(other,root.get());
			return null;
		}
	}

	/** An equal value decoded from its encoding: new objects, except cells the decoder shares, such as small integers. */
	@SuppressWarnings("unchecked")
	static <V extends ACell> V copy(V value) {
		try {
			V copy=(V)CAD3Encoder.INSTANCE.decodeMultiCell(Format.encodeMultiCell(value,true));
			assertEquals(value,copy);
			return copy;
		} catch (BadFormatException e) {
			throw new AssertionError("Value must decode from its own encoding",e);
		}
	}
}
