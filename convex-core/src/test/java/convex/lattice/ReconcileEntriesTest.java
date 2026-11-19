package convex.lattice;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

import org.junit.jupiter.api.Test;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.Index;
import convex.core.data.Keyword;
import convex.core.data.MapEntry;
import convex.core.data.Maps;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;

/** The three-way entry reconcile components opt into, over hash maps and indexes alike. */
@SuppressWarnings("unchecked")
class ReconcileEntriesTest {
	private static final Keyword A=Keyword.intern("a");
	private static final Keyword B=Keyword.intern("b");
	private static final Keyword C=Keyword.intern("c");
	private static final Keyword D=Keyword.intern("d");
	private static final CVMLong ONE=CVMLong.ONE;
	private static final CVMLong TWO=CVMLong.TWO;
	private static final CVMLong THREE=CVMLong.create(3);

	/** Marks an entry both sides changed with both values, so the test sees each call. */
	private static final ALatticeComponent.EntryReconciler<ACell,ACell> BOTH=(key,was,mine,theirs) ->
		MapEntry.create(key,Vectors.of(value(mine),value(theirs)));

	private static ACell value(MapEntry<ACell,ACell> entry) {
		return (entry==null)?null:entry.getValue();
	}

	/** The same entries as a hash map and as an index; nulls in the pairs are stored nil. */
	private static List<AMap<ACell,ACell>> kinds(Object... pairs) {
		AMap<ACell,ACell> map=Maps.empty();
		AMap<ACell,ACell> index=(AMap<ACell,ACell>)(AMap<?,?>)Index.none();
		for (int i=0;i<pairs.length;i+=2) {
			map=map.assoc((ACell)pairs[i],(ACell)pairs[i+1]);
			index=index.assoc((ACell)pairs[i],(ACell)pairs[i+1]);
		}
		return List.of(map,index);
	}

	private static AMap<ACell,ACell> reconcile(int kind,Object[] base,Object[] own,Object[] other) {
		return ALatticeComponent.reconcileEntries(
			(base==null)?null:kinds(base).get(kind),
			(own==null)?null:kinds(own).get(kind),
			(other==null)?null:kinds(other).get(kind),BOTH);
	}

	private static void assertMap(int kind,Object[] expected,AMap<ACell,ACell> actual) {
		assertEquals(kinds(expected).get(kind),actual);
	}

	@Test void anEntryChangedOnOneSideTakesThatSideRemovalIncluded() {
		for (int kind=0;kind<2;kind++) {
			var result=reconcile(kind,
				new Object[] {A,ONE,B,ONE,C,ONE},
				new Object[] {A,TWO,B,ONE},
				new Object[] {A,ONE,B,THREE,C,ONE,D,ONE});
			assertMap(kind,new Object[] {A,TWO,B,THREE,D,ONE},result);
		}
	}

	@Test void anEntryChangedOnBothSidesIsDecidedByTheCaller() {
		for (int kind=0;kind<2;kind++) {
			var result=reconcile(kind,
				new Object[] {A,ONE,B,ONE},
				new Object[] {A,TWO},
				new Object[] {A,THREE,B,TWO});
			assertMap(kind,new Object[] {A,Vectors.of(TWO,THREE),B,Vectors.of(null,TWO)},result);
		}
	}

	@Test void storedNilIsAValueDistinctFromAbsence() {
		for (int kind=0;kind<2;kind++) {
			assertMap(kind,new Object[] {A,null},reconcile(kind,new Object[] {},new Object[] {A,null},new Object[] {}));
			assertMap(kind,new Object[] {},reconcile(kind,new Object[] {A,null},new Object[] {},new Object[] {A,null}));
			assertMap(kind,new Object[] {A,ONE},reconcile(kind,new Object[] {A,null},new Object[] {A,null},new Object[] {A,ONE}));
		}
	}

	@Test void absentMapsAreEmptyOnTheirSide() {
		for (int kind=0;kind<2;kind++) {
			assertMap(kind,new Object[] {A,ONE},reconcile(kind,null,new Object[] {A,ONE},null));
			assertMap(kind,new Object[] {},reconcile(kind,new Object[] {A,ONE},null,new Object[] {A,ONE}));
			assertMap(kind,new Object[] {B,TWO},reconcile(kind,null,null,new Object[] {B,TWO}));
			assertNull(reconcile(kind,new Object[] {A,ONE},new Object[] {A,ONE},null),"an unchanged side keeps the destination's absence");
		}
	}
}
