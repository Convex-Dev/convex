package convex.lattice;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import convex.core.data.ACell;
import convex.core.data.AHashMap;
import convex.core.data.AMap;
import convex.core.data.Index;
import convex.core.data.Keyword;
import convex.core.data.Maps;
import convex.core.data.prim.CVMLong;
import convex.lattice.cursor.ReadOnlyCursorException;
import convex.lattice.cursor.Root;

/** Three-way merge: which side changed what, on both kinds of map and at both tree shapes. */
class MergeChangesTest {

	private static final Keyword A=Keyword.intern("a");
	private static final Keyword B=Keyword.intern("b");
	private static final Keyword X=Keyword.intern("x");
	private static final Keyword Y=Keyword.intern("y");
	private static final ACell ONE=CVMLong.ONE;
	private static final ACell OURS=CVMLong.create(1000);
	private static final ACell THEIRS=CVMLong.create(2000);

	/** Small maps are single leaves and large ones are trees. */
	static Stream<Arguments> maps() {
		List<Arguments> cases=new ArrayList<>();
		for (AMap<Keyword,ACell> kind:List.<AMap<Keyword,ACell>>of(Maps.empty(),Index.none())) {
			for (int size:new int[] {3,40}) cases.add(Arguments.of(filled(kind,size)));
		}
		return cases.stream();
	}

	static Stream<AMap<Keyword,ACell>> kinds() {
		return Stream.of(Maps.empty(),Index.none());
	}

	private static Keyword key(int i) {
		return Keyword.create("k"+i);
	}

	private static AMap<Keyword,ACell> filled(AMap<Keyword,ACell> kind,int size) {
		AMap<Keyword,ACell> map=kind;
		for (int i=0;i<size;i++) map=map.assoc(key(i),CVMLong.create(i));
		return map;
	}

	private static void assertRejected(ACell base,ACell own,ACell other) {
		assertThrows(MergeConflictException.class,() -> LatticeOps.mergeChanges(base,own,other));
	}

	@ParameterizedTest @MethodSource("maps")
	void changesToDifferentLocationsAreBothKept(AMap<Keyword,ACell> base) {
		var own=base.assoc(key(0),OURS);
		var other=base.assoc(key(1),THEIRS);
		assertEquals(own.assoc(key(1),THEIRS),LatticeOps.mergeChanges(base,own,other));
	}

	@ParameterizedTest @MethodSource("maps")
	void aDeleteSurvivesWhereTheOtherSideLeftTheEntryAlone(AMap<Keyword,ACell> base) {
		var deleted=base.dissoc(key(0));
		var changed=base.assoc(key(1),THEIRS);
		var expected=deleted.assoc(key(1),THEIRS);
		assertEquals(expected,LatticeOps.mergeChanges(base,deleted,changed));
		assertEquals(expected,LatticeOps.mergeChanges(base,changed,deleted));
	}

	@ParameterizedTest @MethodSource("maps")
	void changesToTheSameLocationAreRejected(AMap<Keyword,ACell> base) {
		assertRejected(base,base.assoc(key(0),OURS),base.assoc(key(0),THEIRS));
	}

	@ParameterizedTest @MethodSource("maps")
	void aDeleteAgainstAChangeIsRejected(AMap<Keyword,ACell> base) {
		var deleted=base.dissoc(key(0));
		var changed=base.assoc(key(0),THEIRS);
		assertRejected(base,deleted,changed);
		assertRejected(base,changed,deleted);
	}

	@ParameterizedTest @MethodSource("maps")
	void theSameChangeOnBothSidesIsNotAConflict(AMap<Keyword,ACell> base) {
		var both=base.assoc(key(0),OURS);
		var own=both.assoc(key(1),OURS);
		assertEquals(own,LatticeOps.mergeChanges(base,own,both));
	}

	/** A walk between a leaf and a tree also visits equal entries, which must not look like changes. */
	@ParameterizedTest @MethodSource("kinds")
	void mapsOfDifferentShapesMergeTheSame(AMap<Keyword,ACell> kind) {
		var small=filled(kind,3);
		var large=filled(kind,40);
		assertEquals(large.assoc(key(0),THEIRS),LatticeOps.mergeChanges(small,large,small.assoc(key(0),THEIRS)));
		assertEquals(small.assoc(key(0),THEIRS),LatticeOps.mergeChanges(large,small,large.assoc(key(0),THEIRS)));
	}

	@Test void nestedMapsMergeEntryByEntry() {
		AHashMap<Keyword,ACell> inner=Maps.of(X,ONE,Y,ONE);
		AHashMap<Keyword,ACell> base=Maps.of(A,inner);
		var own=base.assoc(A,inner.assoc(X,OURS));
		var other=base.assoc(A,inner.assoc(Y,THEIRS));
		assertEquals(Maps.of(A,Maps.of(X,OURS,Y,THEIRS)),LatticeOps.mergeChanges(base,own,other));
		assertRejected(base,own,base.assoc(A,inner.assoc(X,THEIRS)));
	}

	@ParameterizedTest @MethodSource("kinds")
	void withNoBaseDifferentKeysStillMerge(AMap<Keyword,ACell> kind) {
		var own=kind.assoc(A,OURS);
		var other=kind.assoc(B,THEIRS);
		assertEquals(own.assoc(B,THEIRS),LatticeOps.mergeChanges(null,own,other));
		assertRejected(null,own,kind.assoc(A,THEIRS));
	}

	@Test void anythingButAMapOfTheSameKindIsOneLocation() {
		assertRejected(ONE,OURS,THEIRS);
		AMap<Keyword,ACell> hashed=Maps.of(A,OURS);
		AMap<Keyword,ACell> indexed=Index.of(B,THEIRS);
		assertRejected(null,hashed,indexed);
	}

	@Test void theResultKeepsTheOtherSidesCells() {
		AHashMap<Keyword,ACell> inner=Maps.of(X,ONE);
		AHashMap<Keyword,ACell> base=Maps.of(A,inner,B,inner);
		// An equal value in new cells, as a persist would leave it
		AHashMap<Keyword,ACell> persisted=Maps.of(X,CVMLong.create(1));
		var other=base.assoc(B,persisted);
		assertSame(other,LatticeOps.mergeChanges(base,base,other),"Nothing of ours applies");
		var own=base.assoc(A,Maps.of(X,OURS));
		assertSame(own,LatticeOps.mergeChanges(base,own,base),"Nothing of theirs applies");
		var changedOther=base.assoc(B,Maps.of(X,THEIRS));
		assertSame(changedOther.get(B),LatticeOps.mergeChanges(base,own,changedOther).get(B));
	}

	@Test void reconcileGivesTheMergeTheBaseAndARetrySeesOnlyWhatIsNew() {
		AHashMap<Keyword,ACell> original=Maps.of(A,ONE);
		var root=new Root<AHashMap<Keyword,ACell>>(original);
		var fork=root.fork();
		fork.set(original.assoc(B,OURS));
		root.set(original.assoc(X,THEIRS));
		var firstDestination=root.get();
		var bases=new ArrayList<AHashMap<Keyword,ACell>>();
		var published=fork.reconcile((base,own,other) -> {
			bases.add(base);
			// A write landing during the merge makes the destination's update repeat
			if (bases.size()==1) root.set(other.assoc(Y,THEIRS));
			return LatticeOps.mergeChanges(base,own,other);
		});
		assertEquals(List.of(original,firstDestination),bases);
		assertEquals(Maps.of(A,ONE,B,OURS,X,THEIRS,Y,THEIRS),published);
		assertEquals(published,root.get());
	}

	@Test void aReadOnlyViewRefusesToReconcile() {
		var root=new Root<AHashMap<Keyword,ACell>>(Maps.empty());
		assertThrows(ReadOnlyCursorException.class,() -> root.readOnly().reconcile((base,own,other) -> own));
	}

	@Test void aComponentWithOnlyATwoValueMergeStillUsesIt() {
		AHashMap<Keyword,ACell> original=Maps.of(A,ONE);
		var root=new Root<AHashMap<Keyword,ACell>>(original);
		var fork=root.fork();
		var component=new ALatticeComponent<AHashMap<Keyword,ACell>>(fork) {
			@Override protected AHashMap<Keyword,ACell> mergePublication(AHashMap<Keyword,ACell> own,AHashMap<Keyword,ACell> other) {
				return own.assoc(X,OURS);
			}
		};
		fork.set(original.assoc(B,OURS));
		root.set(original.assoc(Y,THEIRS));
		assertEquals(Maps.of(A,ONE,B,OURS,X,OURS),component.sync(),"The two-value rule decides, not a walk of changes");
	}

	@Test void aComponentThatDefinesNoMergeStillRefuses() {
		AHashMap<Keyword,ACell> original=Maps.of(A,ONE);
		var root=new Root<AHashMap<Keyword,ACell>>(original);
		var fork=root.fork();
		var component=new ALatticeComponent<AHashMap<Keyword,ACell>>(fork) {};
		fork.set(original.assoc(B,OURS));
		root.set(original.assoc(Y,THEIRS));
		assertThrows(UnsupportedOperationException.class,component::sync);
	}
}
