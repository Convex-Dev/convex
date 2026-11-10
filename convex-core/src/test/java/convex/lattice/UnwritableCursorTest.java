package convex.lattice;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import convex.core.crypto.AKeyPair;
import convex.core.data.ACell;
import convex.core.data.Keyword;
import convex.core.data.Maps;
import convex.core.data.SignedData;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;
import convex.lattice.cursor.AForkableCursor;
import convex.lattice.cursor.MissingValueException;
import convex.lattice.cursor.ReadOnlyCursorException;
import convex.lattice.cursor.Root;
import convex.lattice.cursor.SignedCursor;
import convex.lattice.cursor.Transactions;

/**
 * A write lands only where the cursor's place exists. A cursor that is read-only, or
 * whose place does not exist, refuses every write, whatever the values: a write that
 * would change nothing and a compare-and-set that would not match included. An entry
 * is created by writing its container. A view that creates its place when written is
 * a choice of the component supplying it; there, a write that changes nothing stays
 * silent and creates nothing. A transaction reads its place first, so it is refused
 * wherever that read finds nothing, before its callback runs.
 */
public class UnwritableCursorTest {

	private static final Keyword A=Keyword.intern("a");
	private static final Keyword B=Keyword.intern("b");
	private static final CVMLong NEW=CVMLong.create(7);
	private static final CVMLong UNEXPECTED=CVMLong.create(41);
	private static final AKeyPair KEYS=AKeyPair.createSeeded(1234);

	// ===== Places =====

	private record Place(String name,ACell initial,Function<AForkableCursor<ACell>,AForkableCursor<ACell>> view) {
		@Override public String toString() { return name; }
	}

	@SuppressWarnings("unchecked")
	private static AForkableCursor<ACell> signed(AForkableCursor<?> storage) {
		return SignedCursor.create((AForkableCursor<SignedData<ACell>>)storage,KEYS);
	}

	@SuppressWarnings("unchecked")
	private static AForkableCursor<ACell> jsonPath(AForkableCursor<ACell> source,String... segments) {
		var keys=Stream.of(segments).map(Strings::create).toArray(convex.core.data.AString[]::new);
		return (AForkableCursor<ACell>)new JSONValueComponent<>(source).resolve(keys).cursor();
	}

	/** Places that do not exist and read as absent. */
	static Stream<Place> absentWithNowhereToWrite() {
		return Stream.of(
			new Place("missing entry of an existing container",Maps.empty(),source -> source.path(A)),
			new Place("path through a missing container",Maps.empty(),source -> source.path(A,B)),
			new Place("path below nil",null,source -> source.path(A)),
			new Place("path through a scalar",Maps.of(A,CVMLong.ONE),source -> source.path(A,B)),
			new Place("position outside a vector",Vectors.of(CVMLong.ZERO),source -> source.path(CVMLong.create(5))),
			new Place("JSON path through a missing container",Maps.empty(),source -> jsonPath(source,"a","b")),
			new Place("signed view over a path through a missing container",Maps.empty(),source -> signed(source.path(A,B))));
	}

	/** Places that do not exist, read through a view that supplies a default. */
	static Stream<Place> defaultedWithNowhereToWrite() {
		return Stream.of(
			new Place("default view over a missing entry",Maps.empty(),source -> source.<ACell>path(A).withDefault(CVMLong.ZERO)),
			new Place("default view over a path through a missing container",Maps.empty(),
				source -> source.<ACell>path(A,B).withDefault(CVMLong.ZERO)));
	}

	/** Places that do not exist. */
	static Stream<Place> nowhereToWrite() {
		return Stream.concat(absentWithNowhereToWrite(),defaultedWithNowhereToWrite());
	}

	/** Places holding nothing that their view creates when written, and that read as absent. */
	static Stream<Place> absentAndCreatedWhenWritten() {
		return Stream.of(
			new Place("entry of a map component",Maps.empty(),source -> mapEntry(source)),
			new Place("entry of a map component over nil",null,source -> mapEntry(source)));
	}

	/** A place holding nothing that its view creates when written, read through a view that supplies a default. */
	static Stream<Place> defaultedAndCreatedWhenWritten() {
		return Stream.of(
			new Place("default view over the entry of a map component",Maps.empty(),source -> mapEntry(source).withDefault(CVMLong.ZERO)));
	}

	/** Places holding nothing that their view creates when written. */
	static Stream<Place> createdWhenWritten() {
		return Stream.concat(absentAndCreatedWhenWritten(),defaultedAndCreatedWhenWritten());
	}

	@SuppressWarnings({"unchecked","rawtypes"})
	private static AForkableCursor<ACell> mapEntry(AForkableCursor<ACell> source) {
		return new MapComponent<Keyword,ACell>((AForkableCursor)source).path(A);
	}

	private enum Holder {
		ROOT { @Override AForkableCursor<ACell> holding(ACell value) { return Root.create(value); } },
		FORK { @Override AForkableCursor<ACell> holding(ACell value) { return Root.create(value).fork(); } };

		abstract AForkableCursor<ACell> holding(ACell value);
	}

	// ===== Writes =====

	private record Write(String name,Consumer<AForkableCursor<ACell>> action) {
		@Override public String toString() { return name; }
	}

	private static ACell current(AForkableCursor<ACell> cursor) {
		return cursor.getOrDefault(null);
	}

	/** Writes that leave the value as it is. */
	static Stream<Write> unchanging() {
		return Stream.of(
			new Write("set of the value held",cursor -> cursor.set(current(cursor))),
			new Write("getAndSet of the value held",cursor -> cursor.getAndSet(current(cursor))),
			new Write("getAndUpdate changing nothing",cursor -> cursor.getAndUpdate(value -> value)),
			new Write("updateAndGet changing nothing",cursor -> cursor.updateAndGet(value -> value)),
			new Write("getAndAccumulate changing nothing",cursor -> cursor.getAndAccumulate(NEW,(value,x) -> value)),
			new Write("accumulateAndGet changing nothing",cursor -> cursor.accumulateAndGet(NEW,(value,x) -> value)),
			new Write("compareAndSet to the value held",cursor -> cursor.compareAndSet(current(cursor),current(cursor))),
			new Write("compareAndSet that does not match",cursor -> cursor.compareAndSet(UNEXPECTED,NEW)));
	}

	static Stream<Write> changing() {
		return Stream.of(
			new Write("set",cursor -> cursor.set(NEW)),
			new Write("compareAndSet that matches",cursor -> cursor.compareAndSet(current(cursor),NEW)));
	}

	static Stream<Write> writes() {
		return Stream.concat(unchanging(),changing());
	}

	/** The two ways of running a transaction, each given the callback to run. */
	private record Form(String name,BiConsumer<AForkableCursor<ACell>,Consumer<AForkableCursor<ACell>>> runner) {
		void run(AForkableCursor<ACell> cursor,Consumer<AForkableCursor<ACell>> callback) { runner.accept(cursor,callback); }
		@Override public String toString() { return name; }
	}

	static Stream<Form> forms() {
		return Stream.of(
			new Form("transaction",(cursor,callback) -> Transactions.execute(cursor,callback)),
			new Form("exclusive transaction",(cursor,callback) -> Transactions.executeExclusively(cursor,callback)));
	}

	private static <T> Stream<Arguments> each(Stream<Place> places,Supplier<Stream<T>> operations) {
		return places.flatMap(place -> Stream.of(Holder.values()).flatMap(holder ->
			operations.get().map(operation -> Arguments.of(place,holder,operation))));
	}

	static Stream<Arguments> everyWriteWithNowhereToLand() { return each(nowhereToWrite(),UnwritableCursorTest::writes); }
	static Stream<Arguments> unchangingWritesWhereThePlaceIsCreated() { return each(createdWhenWritten(),UnwritableCursorTest::unchanging); }
	static Stream<Arguments> changingWritesWhereThePlaceIsCreated() { return each(createdWhenWritten(),UnwritableCursorTest::changing); }
	static Stream<Arguments> transactionsWhereThePlaceIsAbsent() {
		return each(Stream.concat(absentWithNowhereToWrite(),absentAndCreatedWhenWritten()),UnwritableCursorTest::forms);
	}
	static Stream<Arguments> transactionsThroughADefaultWithNowhereToLand() { return each(defaultedWithNowhereToWrite(),UnwritableCursorTest::forms); }
	static Stream<Arguments> transactionsThroughADefaultWhereThePlaceIsCreated() { return each(defaultedAndCreatedWhenWritten(),UnwritableCursorTest::forms); }

	// ===== The rule =====

	@ParameterizedTest(name="{0}, {1}, {2}")
	@MethodSource("everyWriteWithNowhereToLand")
	void aPlaceThatDoesNotExistRefusesEveryWrite(Place place,Holder holder,Write write) {
		var storage=holder.holding(place.initial);
		var cursor=place.view.apply(storage);

		assertThrows(PathWriteException.class,() -> write.action.accept(cursor));

		assertEquals(place.initial,storage.get());
	}

	@Test void anEntryIsCreatedByWritingItsContainer() {
		Root<ACell> root=Root.create(Maps.empty());
		AForkableCursor<ACell> entry=root.path(A);
		assertThrows(PathWriteException.class,() -> entry.set(NEW));

		root.assoc(A,CVMLong.ONE);
		entry.set(NEW);

		assertEquals(Maps.of(A,NEW),root.get());
	}

	@ParameterizedTest(name="{0}, {1}, {2}")
	@MethodSource("unchangingWritesWhereThePlaceIsCreated")
	void aWriteThatChangesNothingCreatesNothingWhereAViewCreatesItsPlace(Place place,Holder holder,Write write) {
		var storage=holder.holding(place.initial);
		var cursor=place.view.apply(storage);

		assertDoesNotThrow(() -> write.action.accept(cursor));

		assertEquals(place.initial,storage.get(),"Nothing was created");
	}

	@ParameterizedTest(name="{0}, {1}, {2}")
	@MethodSource("changingWritesWhereThePlaceIsCreated")
	void aChangedWriteCreatesThePlaceWhereItsViewDoes(Place place,Holder holder,Write write) {
		var storage=holder.holding(place.initial);
		var cursor=place.view.apply(storage);

		write.action.accept(cursor);

		assertEquals(Maps.of(A,NEW),storage.get());
	}

	@ParameterizedTest(name="{0}")
	@MethodSource("writes")
	void aReadOnlyCursorRefusesEveryWrite(Write write) {
		Root<ACell> root=Root.create(Maps.of(A,CVMLong.ONE));

		assertThrows(ReadOnlyCursorException.class,() -> write.action.accept(root.readOnly()));
		assertThrows(ReadOnlyCursorException.class,() -> write.action.accept(root.readOnly().path(A)));

		assertEquals(Maps.of(A,CVMLong.ONE),root.get());
	}

	@ParameterizedTest(name="{0}")
	@MethodSource("forms")
	void aReadOnlyCursorRefusesEveryTransaction(Form form) {
		Root<ACell> root=Root.create(Maps.of(A,CVMLong.ONE));

		assertThrows(ReadOnlyCursorException.class,() -> form.run(root.readOnly(),draft -> {}));
		assertThrows(ReadOnlyCursorException.class,() -> form.run(root.readOnly().path(A),draft -> draft.set(NEW)));

		assertEquals(Maps.of(A,CVMLong.ONE),root.get());
	}

	// ===== A transaction reads its place =====

	@ParameterizedTest(name="{0}, {1}, {2}")
	@MethodSource("transactionsWhereThePlaceIsAbsent")
	void aTransactionWhereThePlaceIsAbsentIsRefusedBeforeItsCallbackRuns(Place place,Holder holder,Form form) {
		var storage=holder.holding(place.initial);
		var cursor=place.view.apply(storage);
		var runs=new AtomicInteger();

		assertThrows(MissingValueException.class,() -> form.run(cursor,draft -> {
			runs.incrementAndGet();
			draft.set(NEW);
		}));

		assertEquals(0,runs.get());
		assertEquals(place.initial,storage.get());
	}

	@ParameterizedTest(name="{0}")
	@MethodSource("absentWithNowhereToWrite")
	void aRefusedExclusiveTransactionLeavesTheRootFreeToClaim(Place place) {
		Root<ACell> root=Root.create(place.initial);
		var cursor=place.view.apply(root);
		assertThrows(MissingValueException.class,() -> Transactions.executeExclusively(cursor,draft -> draft.set(NEW)));

		Transactions.executeExclusively(root,draft -> draft.set(NEW));

		assertEquals(NEW,root.get());
	}

	@Test void aTransactionRunsOnceItsPlaceHasBeenCreated() {
		Root<ACell> root=Root.create(Maps.empty());
		AForkableCursor<ACell> entry=root.path(A);
		assertThrows(MissingValueException.class,() -> Transactions.execute(entry,draft -> draft.set(NEW)));

		root.assoc(A,null);
		Transactions.execute(entry,draft -> draft.set(NEW));

		assertEquals(Maps.of(A,NEW),root.get());
	}

	@ParameterizedTest(name="{0}, {1}, {2}")
	@MethodSource("transactionsThroughADefaultWithNowhereToLand")
	void aTransactionThroughADefaultStartsFromItAndIsRefusedWithNowhereToLand(Place place,Holder holder,Form form) {
		var storage=holder.holding(place.initial);
		var cursor=place.view.apply(storage);
		var seen=new AtomicReference<ACell>();

		assertThrows(PathWriteException.class,() -> form.run(cursor,draft -> {
			seen.set(draft.get());
			draft.set(NEW);
		}));

		assertEquals(CVMLong.ZERO,seen.get());
		assertEquals(place.initial,storage.get());
	}

	@ParameterizedTest(name="{0}, {1}, {2}")
	@MethodSource("transactionsThroughADefaultWhereThePlaceIsCreated")
	void aTransactionThroughADefaultStartsFromItAndCreatesThePlaceWhereItsViewDoes(Place place,Holder holder,Form form) {
		var storage=holder.holding(place.initial);
		var cursor=place.view.apply(storage);
		var seen=new AtomicReference<ACell>();

		form.run(cursor,draft -> seen.set(draft.get()));
		assertEquals(place.initial,storage.get(),"Nothing was created");

		form.run(cursor,draft -> draft.set(NEW));

		assertEquals(CVMLong.ZERO,seen.get());
		assertEquals(Maps.of(A,NEW),storage.get());
	}

	// ===== A fork publishes by writing =====

	@Test void aForkWhoseContainerHasGoneIsRefusedAtSyncWithNothingToPublish() {
		Root<ACell> root=Root.create(Maps.of(A,Maps.of(B,CVMLong.ONE)));
		var fork=root.<ACell>path(A,B).fork();
		root.set(Maps.empty());

		assertThrows(PathWriteException.class,() -> fork.sync((own,other) -> own));

		assertEquals(Maps.empty(),root.get());
	}

	@Test void aForkWhoseEntryHasGoneIsRefusedAtSync() {
		Root<ACell> root=Root.create(Maps.of(A,Maps.of(B,CVMLong.ONE)));
		var fork=root.<ACell>path(A,B).fork();
		fork.set(NEW);
		root.path(A).set(Maps.empty());

		assertThrows(PathWriteException.class,() -> fork.sync((own,other) -> own));

		assertEquals(NEW,fork.get());
		assertEquals(Maps.of(A,Maps.empty()),root.get(),"The deleted entry does not come back");
	}

	@Test void aForkRefusedAtSyncKeepsItsEdits() {
		Root<ACell> root=Root.create(Maps.of(A,Maps.of(B,CVMLong.ONE)));
		var fork=root.<ACell>path(A,B).fork();
		fork.set(NEW);
		root.set(Maps.empty());

		assertThrows(PathWriteException.class,() -> fork.sync((own,other) -> own));

		assertEquals(NEW,fork.get());
		assertEquals(Maps.empty(),root.get());
	}
}
