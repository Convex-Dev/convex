package convex.lattice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongFunction;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import convex.core.crypto.AKeyPair;
import convex.core.data.ACell;
import convex.core.data.AHashMap;
import convex.core.data.Keyword;
import convex.core.data.Maps;
import convex.core.data.SignedData;
import convex.core.data.Strings;
import convex.core.data.prim.CVMLong;
import convex.lattice.cursor.AForkableCursor;
import convex.lattice.cursor.ProjectionCursor;
import convex.lattice.cursor.ReadOnlyCursorException;
import convex.lattice.cursor.Root;
import convex.lattice.cursor.SignedCursor;
import convex.lattice.cursor.StampedCursor;
import convex.lattice.cursor.Transactions;

/**
 * Compare-and-set is the atomic update "if the value equals the expected one then the
 * new value, otherwise unchanged", and its result says whether the value matched. That
 * holds for every kind of view, over a root and over a fork. The checks compare values
 * only: the contract says nothing about which object a cursor holds, and a cursor that
 * signs, stamps or builds containers writes the new value as it writes any other.
 *
 * <p>Other writes are injected at the two moments a write has: after it has read, and
 * as it lands. A root offers both directly. A fork keeps its working root to itself,
 * so there a pass-through view between the cursor and the fork supplies them.</p>
 */
public class CompareAndSetContractTest {

	private static final Keyword KEY=Keyword.intern("key");
	private static final Keyword OTHER=Keyword.intern("other");
	private static final Keyword NEST=Keyword.intern("nest");
	private static final Keyword N=Keyword.intern("n");
	private static final Keyword TIME=Keyword.intern("time");
	private static final CVMLong DEFAULT=CVMLong.create(99);
	private static final CVMLong ELSEWHERE=CVMLong.create(7);
	private static final AKeyPair KEYS=AKeyPair.createSeeded(1234);
	private static final Runnable NOTHING=() -> {};

	// ===== Kinds of view =====

	private interface View {
		/** Builds the view over a source, counting each signature or stamp it makes. */
		AForkableCursor<ACell> over(AForkableCursor<AHashMap<Keyword,ACell>> source,AtomicInteger authored);
	}

	/**
	 * @param value The value the view shows for a number
	 * @param stored What the holder stores at the view's place for that value
	 * @param plain Removes what the view's own writing adds, such as a stamp
	 * @param nested Whether the view's place is under {@link #NEST}
	 * @param createsItsPlace Whether a write through the view creates its place where none exists
	 */
	private record Kind(String name,View view,LongFunction<ACell> value,LongFunction<ACell> stored,
			UnaryOperator<ACell> plain,boolean nested,boolean createsItsPlace) {
		@Override public String toString() { return name; }
	}

	private static Kind plainKind(String name,boolean createsItsPlace,View view) {
		return new Kind(name,view,CVMLong::create,CVMLong::create,value -> value,false,createsItsPlace);
	}

	private static AHashMap<Keyword,ACell> numbered(long n) {
		return Maps.of(N,CVMLong.create(n));
	}

	@SuppressWarnings("unchecked")
	private static AForkableCursor<ACell> signed(AForkableCursor<?> storage,AtomicInteger authored) {
		return SignedCursor.create((AForkableCursor<SignedData<ACell>>)storage,value -> {
			authored.incrementAndGet();
			return KEYS.signData(value);
		});
	}

	@SuppressWarnings("unchecked")
	private static AForkableCursor<ACell> stamped(AForkableCursor<ACell> storage,AtomicInteger authored) {
		return StampedCursor.create(storage,() -> CVMLong.create(authored.incrementAndGet()),
			(value,time) -> ((AHashMap<Keyword,ACell>)value).assoc(TIME,time));
	}

	@SuppressWarnings("unchecked")
	private static AForkableCursor<ACell> jsonPath(AForkableCursor<AHashMap<Keyword,ACell>> source) {
		return (AForkableCursor<ACell>)new JSONValueComponent<>(source).resolve(Strings.create("key")).cursor();
	}

	@SuppressWarnings("unchecked")
	private static ACell unstamped(ACell value) {
		return value==null?null:((AHashMap<Keyword,ACell>)value).dissoc(TIME);
	}

	static Stream<Kind> kinds() {
		return Stream.of(
			plainKind("path",false,(source,authored) -> source.path(KEY)),
			plainKind("default view over a path",false,(source,authored) -> source.<ACell>path(KEY).withDefault(DEFAULT)),
			plainKind("projection",true,(source,authored) -> ProjectionCursor.<AHashMap<Keyword,ACell>,ACell>create(source,
				map -> map==null?null:map.get(KEY),(map,value) -> map.assoc(KEY,value))),
			new Kind("signed view over a path",(source,authored) -> signed(source.path(KEY),authored),
				CVMLong::create,n -> KEYS.signData(CVMLong.create(n)),value -> value,false,false),
			new Kind("stamped view over a path",(source,authored) -> stamped(source.path(KEY),authored),
				CompareAndSetContractTest::numbered,CompareAndSetContractTest::numbered,CompareAndSetContractTest::unstamped,false,false),
			plainKind("lattice path",true,(source,authored) -> LatticeComponent.resolve(source,null,KEY)),
			plainKind("map entry",true,(source,authored) -> new MapComponent<Keyword,ACell>(source).path(KEY)),
			plainKind("JSON path",false,(source,authored) -> jsonPath(source)),
			new Kind("path over a path",(source,authored) -> source.<ACell>path(NEST).path(KEY),
				CVMLong::create,CVMLong::create,value -> value,true,false));
	}

	private enum Holder { ROOT, FORK }

	private static Stream<Arguments> subjects(Stream<Kind> kinds) {
		return kinds.flatMap(kind -> Stream.of(Holder.values()).map(holder -> Arguments.of(kind,holder)));
	}

	static Stream<Arguments> subjects() { return subjects(kinds()); }
	static Stream<Arguments> subjectsThatCreateTheirPlace() { return subjects(kinds().filter(Kind::createsItsPlace)); }
	static Stream<Arguments> subjectsWhosePlaceMustExist() { return subjects(kinds().filter(kind -> !kind.createsItsPlace)); }

	// ===== A cursor under test, and the root or fork its writes land in =====

	private static final class Subject {
		final Kind kind;
		final AtomicInteger authored=new AtomicInteger();
		final AForkableCursor<AHashMap<Keyword,ACell>> holder;
		final AForkableCursor<ACell> cursor;
		private Runnable afterRead=NOTHING;
		private Runnable asItLands=NOTHING;

		Subject(Kind kind,Holder holding,boolean stored) {
			this.kind=kind;
			AHashMap<Keyword,ACell> initial=Maps.of(OTHER,CVMLong.ZERO);
			initial=stored
				?initial.assoc(KEY,kind.stored.apply(1)).assoc(NEST,Maps.of(KEY,kind.stored.apply(1)))
				:initial.assoc(NEST,Maps.empty());
			AForkableCursor<AHashMap<Keyword,ACell>> source;
			if (holding==Holder.ROOT) {
				holder=new Root<>(initial) {
					@Override public AHashMap<Keyword,ACell> get() {
						var value=super.get();
						fire(true);
						return value;
					}

					@Override protected boolean install(AHashMap<Keyword,ACell> held,AHashMap<Keyword,ACell> next) {
						fire(false);
						return super.install(held,next);
					}
				};
				source=holder;
			} else {
				holder=Root.create(initial).fork();
				source=ProjectionCursor.create(holder,
					value -> { fire(true); return value; },
					(current,value) -> { fire(false); return value; });
			}
			cursor=kind.view.over(source,authored);
		}

		/** Runs the injected write once, since the moment it is injected at may come again. */
		private void fire(boolean read) {
			Runnable action=read?afterRead:asItLands;
			if (read) afterRead=NOTHING; else asItLands=NOTHING;
			action.run();
		}

		ACell value(long n) { return kind.value.apply(n); }

		/** The view's own place, written directly in the holder. */
		AForkableCursor<ACell> place() {
			return kind.nested?holder.path(NEST,KEY):holder.path(KEY);
		}

		AForkableCursor<ACell> elsewhere() { return holder.path(OTHER); }

		/** What the cursor shows, without anything its own writing added. */
		ACell shown() { return kind.plain.apply(cursor.getOrDefault(null)); }

		void changeElsewhereAfterTheRead() { afterRead=() -> elsewhere().set(ELSEWHERE); }
		void changeElsewhereAsItLands() { asItLands=() -> elsewhere().set(ELSEWHERE); }
		void changeItsOwnValueAsItLands() { asItLands=() -> place().set(kind.stored.apply(3)); }
	}

	// ===== The result says whether the value matched =====

	@ParameterizedTest(name="{0}, {1}")
	@MethodSource("subjects")
	void aMatchWritesTheNewValueAsSetDoes(Kind kind,Holder holder) {
		var subject=new Subject(kind,holder,true);
		var twin=new Subject(kind,holder,true);

		assertTrue(subject.cursor.compareAndSet(subject.value(1),subject.value(2)));
		twin.cursor.set(twin.value(2));

		assertEquals(twin.holder.get(),subject.holder.get());
	}

	@ParameterizedTest(name="{0}, {1}")
	@MethodSource("subjects")
	void aMismatchLeavesTheValueUnchanged(Kind kind,Holder holder) {
		var subject=new Subject(kind,holder,true);
		var before=subject.holder.get();

		assertFalse(subject.cursor.compareAndSet(subject.value(9),subject.value(2)));

		assertEquals(before,subject.holder.get());
		assertEquals(0,subject.authored.get(),"Nothing was signed or stamped");
	}

	@ParameterizedTest(name="{0}, {1}")
	@MethodSource("subjectsThatCreateTheirPlace")
	void nullIsTheExpectedValueWhereAViewCreatesItsPlace(Kind kind,Holder holder) {
		var subject=new Subject(kind,holder,false);
		var twin=new Subject(kind,holder,false);

		assertTrue(subject.cursor.compareAndSet(null,subject.value(2)));
		twin.cursor.set(twin.value(2));

		assertEquals(twin.holder.get(),subject.holder.get());
	}

	@ParameterizedTest(name="{0}, {1}")
	@MethodSource("subjectsWhosePlaceMustExist")
	void aPlaceThatDoesNotExistRefusesIt(Kind kind,Holder holder) {
		var subject=new Subject(kind,holder,false);
		var before=subject.holder.get();

		assertThrows(PathWriteException.class,() -> subject.cursor.compareAndSet(null,subject.value(2)));

		assertEquals(before,subject.holder.get());
	}

	// ===== Only a change to the cursor's own value fails it =====

	@ParameterizedTest(name="{0}, {1}")
	@MethodSource("subjects")
	void aChangeElsewhereAsItLandsDoesNotFailIt(Kind kind,Holder holder) {
		var subject=new Subject(kind,holder,true);
		subject.changeElsewhereAsItLands();

		assertTrue(subject.cursor.compareAndSet(subject.value(1),subject.value(2)));

		assertEquals(subject.value(2),subject.shown());
		assertEquals(ELSEWHERE,subject.elsewhere().get(),"The other write is kept");
	}

	/** A write that matched on its first attempt must look again once that attempt has lost. */
	@ParameterizedTest(name="{0}, {1}")
	@MethodSource("subjects")
	void aChangeToItsOwnValueAsItLandsFailsItAndIsKept(Kind kind,Holder holder) {
		var subject=new Subject(kind,holder,true);
		subject.changeItsOwnValueAsItLands();

		assertFalse(subject.cursor.compareAndSet(subject.value(1),subject.value(2)));

		assertEquals(subject.value(3),subject.shown());
	}

	@ParameterizedTest(name="{0}, {1}")
	@MethodSource("subjects")
	void aNewValueEqualToTheOneHeldMatchesAndWritesNothing(Kind kind,Holder holder) {
		var subject=new Subject(kind,holder,true);
		var stored=subject.place().get();
		subject.changeElsewhereAfterTheRead();

		assertTrue(subject.cursor.compareAndSet(subject.value(1),subject.value(1)));

		assertEquals(stored,subject.place().get());
		assertEquals(ELSEWHERE,subject.elsewhere().get(),"The other write is kept");
		assertEquals(0,subject.authored.get(),"Nothing was signed or stamped");
	}

	// ===== A read-only source refuses it =====

	@ParameterizedTest(name="{0}, {1}")
	@MethodSource("subjects")
	void aReadOnlySourceRefusesItWhateverTheValues(Kind kind,Holder holder) {
		var subject=new Subject(kind,holder,true);
		var view=kind.view.over(subject.holder.readOnly(),subject.authored);
		var before=subject.holder.get();

		assertThrows(ReadOnlyCursorException.class,() -> view.compareAndSet(subject.value(9),subject.value(2)));
		assertThrows(ReadOnlyCursorException.class,() -> view.compareAndSet(subject.value(1),subject.value(2)));
		assertThrows(ReadOnlyCursorException.class,() -> view.compareAndSet(subject.value(1),subject.value(1)));

		assertEquals(before,subject.holder.get());
	}

	// ===== Transactions commit by it =====

	private static int runsOfATransaction(Subject subject,boolean exclusive) {
		var runs=new AtomicInteger();
		if (exclusive) {
			Transactions.executeExclusively(subject.cursor,draft -> { runs.incrementAndGet(); draft.set(subject.value(2)); });
		} else {
			Transactions.execute(subject.cursor,draft -> { runs.incrementAndGet(); draft.set(subject.value(2)); });
		}
		return runs.get();
	}

	@ParameterizedTest(name="{0}, {1}")
	@MethodSource("subjects")
	void aTransactionCommitsOverAChangeElsewhereWithoutRunningAgain(Kind kind,Holder holder) {
		var subject=new Subject(kind,holder,true);
		subject.changeElsewhereAsItLands();

		assertEquals(1,runsOfATransaction(subject,false));

		assertEquals(subject.value(2),subject.shown());
		assertEquals(ELSEWHERE,subject.elsewhere().get(),"The other write is kept");
	}

	@ParameterizedTest(name="{0}, {1}")
	@MethodSource("subjects")
	void anExclusiveTransactionCommitsOverAChangeElsewhere(Kind kind,Holder holder) {
		var subject=new Subject(kind,holder,true);
		subject.changeElsewhereAsItLands();

		assertEquals(1,runsOfATransaction(subject,true));

		assertEquals(subject.value(2),subject.shown());
		assertEquals(ELSEWHERE,subject.elsewhere().get(),"The other write is kept");
	}

	@ParameterizedTest(name="{0}, {1}")
	@MethodSource("subjects")
	void aTransactionRunsAgainWhenItsOwnValueChangedAsItCommitted(Kind kind,Holder holder) {
		var subject=new Subject(kind,holder,true);
		subject.changeItsOwnValueAsItLands();

		assertEquals(2,runsOfATransaction(subject,false));

		assertEquals(subject.value(2),subject.shown());
	}
}
