package convex.test.lattice;

import java.util.function.Consumer;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.CAD3Encoder;
import convex.core.data.Format;
import convex.core.exceptions.BadFormatException;
import convex.core.util.Utils;
import convex.lattice.ALatticeComponent;
import convex.lattice.MergeConflictException;
import convex.lattice.cursor.AForkableCursor;
import convex.lattice.cursor.Cursors;
import convex.lattice.cursor.ForkedCursor;
import convex.lattice.cursor.Root;

/**
 * Checks that a lattice component keeps the merge and reconcile rules every component
 * keeps, given sample deliveries or domain edits, for use in tests. Each check returns
 * the first rule the component breaks, as a sentence, or null if it keeps them all, so
 * a test asserts that the result is null.
 *
 * <p>A check binds the component to cursors it makes, through
 * {@link ALatticeComponent#rebind}, and never changes the component itself. Held cells
 * are kept wherever a result equals them: where a rule expects the held object itself,
 * an equal value breaks it. A sync may fail with a {@link MergeConflictException}
 * where the rules allow a conflict; any other exception from the component propagates.</p>
 */
public final class ComponentChecks {

	private ComponentChecks() {}

	/**
	 * Checks a component with two valid deliveries, which may tie: an empty component
	 * takes a delivery as it is; an absent, repeated or re-decoded delivery keeps the
	 * held object; and a fork publishes by the reconcile rule, where a change on both
	 * sides gives the merge in one order or a conflict that changes neither side.
	 *
	 * @param <V> Value type
	 * @param component Component configured for the deliveries
	 * @param a First valid delivery
	 * @param b Second valid delivery
	 * @return The first rule broken, or null
	 */
	public static <V extends ACell> String checkDeliveries(ALatticeComponent<V> component, V a, V b) {
		return run(() -> deliveries(component,a,b));
	}

	/**
	 * Checks a component with two valid deliveries whose merge does not depend on
	 * order, as {@link #checkDeliveries} does and further: merging in either order or
	 * any grouping gives the same value, a fork whose change the destination does not
	 * hold publishes that value, and one whose change it already holds leaves the
	 * destination's object as it is. A conflict on a change on both sides is allowed.
	 *
	 * @param <V> Value type
	 * @param component Component configured for the deliveries
	 * @param a First valid delivery
	 * @param b Second valid delivery
	 * @return The first rule broken, or null
	 */
	public static <V extends ACell> String checkCommutingDeliveries(ALatticeComponent<V> component, V a, V b) {
		return run(() -> {
			deliveries(component,a,b);
			V ab=merged(component,a,b);
			requireEqual(ab,merged(component,b,a),"Merging in either order must give the same value");
			requireEqual(ab,merged(component,ab),"Merging the merged value into an empty component must give it");
			requireEqual(ab,mergeInto(component,merged(component,a),ab),"Merging the merged value into the first delivery must give it");
			requireEqual(ab,mergeInto(component,merged(component,b),ab),"Merging the merged value into the second delivery must give it");

			Root<V> root=Cursors.root(null);
			ForkedCursor<V> fork=root.fork();
			ALatticeComponent<V> working=component.rebind(fork);
			working.merge(a);
			component.rebind(root).merge(b);
			V result=syncOrConflict(working,fork,root);
			if (result!=null) requireEqual(ab,result,"When both sides changed, sync must publish the merged value");

			root=Cursors.root(null);
			fork=root.fork();
			working=component.rebind(fork);
			working.merge(a);
			ALatticeComponent<V> destination=component.rebind(root);
			destination.merge(a);
			destination.merge(b);
			V held=root.get();
			result=syncOrConflict(working,fork,root);
			if (result!=null) require(held==result,"A fork whose change the destination already holds must leave the destination's object as it is");
		});
	}

	/**
	 * Checks that each refused delivery leaves a held value as it is, as the same
	 * object, whether merged or delivered through {@link ALatticeComponent#mergeAt}.
	 * A refused delivery need not be of the component's value type. The held value must
	 * already have any container a refused delivery brings with it, since a component
	 * may keep a delivered container whose contents it refuses.
	 *
	 * @param <V> Value type
	 * @param component Component configured for the deliveries
	 * @param held Valid held value
	 * @param refused Deliveries the component refuses
	 * @return The first rule broken, or null
	 */
	public static <V extends ACell> String checkRefusedDeliveries(ALatticeComponent<V> component, V held, ACell... refused) {
		return run(() -> {
			for (int i=0;i<refused.length;i++) {
				Root<V> root=Cursors.root(held);
				component.rebind(root).merge(refused[i]);
				require(held==root.get(),"Refused delivery "+i+" must leave the held object as it is when merged");
				root=Cursors.root(held);
				component.rebind(root).mergeAt(refused[i]);
				require(held==root.get(),"Refused delivery "+i+" must leave the held object as it is when delivered");
			}
		});
	}

	/**
	 * Checks that a component hands deliveries for one of its children to that child,
	 * neither checking them on the child's behalf nor merging them without the child.
	 * Each delivery is made to the component holding its current value, at the child's
	 * key as a path and within that value; each way must change the value at the key as
	 * delivering it to the child alone does, and change nothing else. Where the child
	 * keeps its value, the component must keep its object. A check the component makes of
	 * a child's value is seen only for a delivery the child judges differently.
	 *
	 * @param <V> Value type, a map
	 * @param component Component holding a value
	 * @param key Key of the child in the component's value
	 * @param child The component's child at the key
	 * @param deliveries Deliveries for the child, of any type
	 * @return The first rule broken, or null
	 */
	public static <V extends AMap<?,?>> String checkChildDeliveries(ALatticeComponent<V> component, ACell key,
			ALatticeComponent<?> child, ACell... deliveries) {
		return run(() -> {
			require(component.exists() && component.value()!=null,"The component must hold a value");
			V held=component.value();
			ACell place=held.get(key);
			requireEqual(place,child.exists()?child.value():null,"The child must hold the component's value at the key");
			for (int i=0;i<deliveries.length;i++) {
				ACell delivery=deliveries[i];
				ACell expected=deliveredToChild(child,place,delivery);
				Root<V> root=Cursors.root(held);
				component.rebind(root).mergeAt(delivery,key);
				requireChildPlace(held,root.get(),key,place,expected,"Delivery "+i+" at the child's path");
				// A whole value cannot carry an absent entry
				if (delivery==null) continue;
				@SuppressWarnings("unchecked")
				V whole=(V)held.assoc(key,delivery);
				require(whole!=null,"The key must be a valid key of the component's value");
				root=Cursors.root(held);
				component.rebind(root).mergeAt(whole);
				requireChildPlace(held,root.get(),key,place,expected,"Delivery "+i+" within the component's value");
			}
		});
	}

	/**
	 * Checks a component through two domain edits, each of which changes the value,
	 * applied in a fork and at its destination. A fork where neither changed leaves the
	 * destination's object as it is; where only the destination changed, the fork takes
	 * the destination's object; where only the fork changed, the destination takes the
	 * fork's value; and where both changed, sync gives the value of the two edits
	 * applied one after the other, in either order, or fails with a conflict that
	 * changes neither side. Each edit must give the same value whenever it is applied to
	 * the same value, so any clock it reads must be fixed.
	 *
	 * @param <V> Value type
	 * @param <C> Component type, which its rebind returns
	 * @param component Component configured for the edits
	 * @param start Value both sides start from
	 * @param editA Edit made in the fork
	 * @param editB Edit made at the destination
	 * @return The first rule broken, or null
	 */
	public static <V extends ACell,C extends ALatticeComponent<V>> String checkConcurrentEdits(C component, V start,
			Consumer<? super C> editA, Consumer<? super C> editB) {
		return run(() -> {
			V va=edited(component,start,editA);
			V vb=edited(component,start,editB);
			requireEqual(va,edited(component,start,editA),"An edit must give the same value each time it is applied");
			requireEqual(vb,edited(component,start,editB),"An edit must give the same value each time it is applied");
			require(!Utils.equals(start,va) && !Utils.equals(start,vb),"Each edit must change the value");

			Root<V> root=Cursors.root(start);
			ForkedCursor<V> fork=root.fork();
			rebound(component,fork).sync();
			require(start==root.get(),"A fork where neither side changed must leave the destination's object as it is");

			root=Cursors.root(start);
			fork=root.fork();
			C working=rebound(component,fork);
			editB.accept(rebound(component,root));
			V published=root.get();
			working.sync();
			require(published==root.get() && published==fork.get(),"A fork where only the destination changed must take the destination's object");

			root=Cursors.root(start);
			fork=root.fork();
			working=rebound(component,fork);
			editA.accept(working);
			working.sync();
			requireEqual(va,root.get(),"A fork where only the fork changed must publish its value");

			root=Cursors.root(start);
			fork=root.fork();
			working=rebound(component,fork);
			editA.accept(working);
			editB.accept(rebound(component,root));
			V result=syncOrConflict(working,fork,root);
			if (result!=null) {
				require(result.equals(edited(component,start,editA,editB))||result.equals(edited(component,start,editB,editA)),
					"When both sides changed, sync must publish the two edits applied in one order");
			}
		});
	}

	/** The rules one valid delivery keeps on its own. */
	private static <V extends ACell> void delivery(ALatticeComponent<V> component, V v) {
		V held=merged(component,v);
		requireEqual(v,held,"An empty component must take a valid delivery as it is");
		require(held==mergeInto(component,held,null),"Merging an absent delivery must keep the held object");
		require(held==mergeInto(component,held,v),"Merging a delivery again must keep the held object");
		require(held==mergeInto(component,held,copy(v)),"Merging an equal, decoded copy of a delivery must keep the held object");
	}

	/** The rules any two valid deliveries keep. */
	private static <V extends ACell> void deliveries(ALatticeComponent<V> component, V a, V b) {
		delivery(component,a);
		delivery(component,b);
		V ab=merged(component,a,b);
		require(ab==mergeInto(component,ab,a) && ab==mergeInto(component,ab,b),"Replaying a delivery must keep the held object");
		require(ab==mergeInto(component,ab,copy(ab)),"Merging an equal, decoded copy of the held value must keep the held object");

		V va=merged(component,a);
		Root<V> root=Cursors.root(va);
		ForkedCursor<V> fork=root.fork();
		require(va==component.rebind(fork).sync() && va==root.get(),"A fork where neither side changed must leave the destination's object as it is");

		root=Cursors.root(va);
		fork=root.fork();
		ALatticeComponent<V> working=component.rebind(fork);
		component.rebind(root).merge(b);
		V published=root.get();
		working.sync();
		require(published==root.get() && published==fork.get(),"A fork where only the destination changed must take the destination's object");

		root=Cursors.root(va);
		fork=root.fork();
		working=component.rebind(fork);
		working.merge(b);
		working.sync();
		requireEqual(ab,root.get(),"A fork where only the fork changed must publish its value");

		root=Cursors.root(null);
		fork=root.fork();
		working=component.rebind(fork);
		working.merge(a);
		component.rebind(root).merge(b);
		V result=syncOrConflict(working,fork,root);
		if (result!=null) require(result.equals(ab)||result.equals(merged(component,b,a)),"When both sides changed, sync must publish a merge of the two in one order");
	}

	/** Merges a delivery into a component holding a value, returning the value it then holds. */
	private static <V extends ACell> V mergeInto(ALatticeComponent<V> component, V held, V delivery) {
		Root<V> root=Cursors.root(held);
		V result=component.rebind(root).merge(delivery);
		require(root.get()==result,"Merge must return the value the component then holds");
		return result;
	}

	/** The value an empty component holds after merging the deliveries in order. */
	@SafeVarargs
	private static <V extends ACell> V merged(ALatticeComponent<V> component, V... deliveries) {
		V held=null;
		for (V d:deliveries) held=mergeInto(component,held,d);
		return held;
	}

	/** The value a component holding a start value holds after the edits, applied in order to one instance. */
	@SafeVarargs
	private static <V extends ACell,C extends ALatticeComponent<V>> V edited(C component, V start, Consumer<? super C>... edits) {
		Root<V> root=Cursors.root(start);
		C target=rebound(component,root);
		for (Consumer<? super C> edit:edits) edit.accept(target);
		return root.get();
	}

	/** The value a child holding a place holds after a delivery. */
	@SuppressWarnings("unchecked")
	private static <T extends ACell> ACell deliveredToChild(ALatticeComponent<T> child, ACell place, ACell delivery) {
		Root<T> root=Cursors.root((T)place);
		child.rebind(root).mergeAt(delivery);
		return root.get();
	}

	/**
	 * Requires a container's value after a delivery for its child to differ from the held
	 * value only at the child's key, where it holds what the child alone holds.
	 */
	private static void requireChildPlace(AMap<?,?> held, AMap<?,?> result, ACell key, ACell place, ACell expected, String delivery) {
		if (expected==place) {
			require(result==held,delivery+" must leave the component's object as it is, as the child keeps its value");
			return;
		}
		requireEqual(expected,(result==null)?null:result.get(key),delivery+" must give the value the child gives");
		requireEqual(held.dissoc(key),(result==null)?null:result.dissoc(key),delivery+" must change nothing but the child's value");
	}

	/** The component's counterpart on a cursor; a component's rebind returns its own class. */
	@SuppressWarnings("unchecked")
	private static <V extends ACell,C extends ALatticeComponent<V>> C rebound(C component, AForkableCursor<V> cursor) {
		return (C)component.rebind(cursor);
	}

	/**
	 * Syncs a fork whose working value and destination both changed. Returns the
	 * published value, or null after requiring that a conflict changed neither side.
	 */
	private static <V extends ACell> V syncOrConflict(ALatticeComponent<V> working, ForkedCursor<V> fork, Root<V> root) {
		V own=fork.get();
		V other=root.get();
		V published;
		try {
			published=working.sync();
		} catch (MergeConflictException e) {
			require(own==fork.get() && other==root.get(),"A conflict must change neither side");
			return null;
		}
		require(published==root.get(),"Sync must return the value it published");
		return published;
	}

	/** An equal value decoded from a value's encoding. */
	@SuppressWarnings("unchecked")
	private static <V extends ACell> V copy(V value) {
		V copy;
		try {
			copy=(V)CAD3Encoder.INSTANCE.decodeMultiCell(Format.encodeMultiCell(value,true));
		} catch (BadFormatException e) {
			throw new Violation("A value must decode from its own encoding: "+e.getMessage());
		}
		requireEqual(value,copy,"A value must decode to an equal value");
		return copy;
	}

	private static void require(boolean holds, String rule) {
		if (!holds) throw new Violation(rule);
	}

	private static void requireEqual(ACell expected, ACell actual, String rule) {
		if (!Utils.equals(expected,actual)) throw new Violation(rule+": expected "+brief(expected)+" but was "+brief(actual));
	}

	private static String brief(ACell value) {
		String s=String.valueOf(value);
		return (s.length()>200)?s.substring(0,200)+"...":s;
	}

	/** Runs checks, returning the first rule broken or null. */
	private static String run(Runnable checks) {
		try {
			checks.run();
			return null;
		} catch (Violation v) {
			return v.getMessage();
		}
	}

	/** A broken rule, which ends a check. */
	private static final class Violation extends RuntimeException {
		private static final long serialVersionUID=1L;
		Violation(String rule) { super(rule,null,false,false); }
	}
}
