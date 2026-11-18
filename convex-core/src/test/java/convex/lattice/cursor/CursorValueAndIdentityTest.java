package convex.lattice.cursor;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import convex.core.data.ABlob;
import convex.core.data.ACell;
import convex.core.data.AHashMap;
import convex.core.data.Blobs;
import convex.core.data.Keyword;
import convex.core.data.Maps;

/**
 * Atomic operations decide by value, never by which object represents it, and they
 * do so the same way on every kind of storage. Which object a cursor holds afterwards
 * follows one rule: a changed write holds the object written, and a write of an equal
 * value keeps the object already held.
 */
public class CursorValueAndIdentityTest {

	private static final Keyword KEY=Keyword.intern("key");
	private static final Keyword OTHER=Keyword.intern("other");

	/** Equal for equal seeds, and a new object on every call. */
	private static ABlob blob(long seed) {
		return Blobs.createRandom(new Random(seed),1000);
	}

	// ===== Kinds of storage =====

	private record Storage(String name,Function<ABlob,AForkableCursor<ABlob>> holding) {
		@Override public String toString() { return name; }
	}

	private static AForkableCursor<ABlob> pathOver(Function<Root<AHashMap<Keyword,ACell>>,AForkableCursor<AHashMap<Keyword,ACell>>> source,ABlob value) {
		Root<AHashMap<Keyword,ACell>> root=Root.create(Maps.of(KEY,value));
		return source.apply(root).path(KEY);
	}

	static Stream<Storage> storage() {
		return Stream.of(
			new Storage("root",Root::create),
			new Storage("fork",value -> Root.create(value).fork()),
			new Storage("path",value -> pathOver(root -> root,value)),
			new Storage("path over a fork",value -> pathOver(Root::fork,value)),
			new Storage("default view",value -> Root.create(value).withDefault(blob(99))));
	}

	// ===== Ways of writing a value =====

	private record Writer(String name,BiConsumer<AForkableCursor<ABlob>,ABlob> write) {
		@Override public String toString() { return name; }
	}

	static Stream<Writer> writers() {
		return Stream.of(
			new Writer("set",(cursor,value) -> cursor.set(value)),
			new Writer("getAndSet",(cursor,value) -> cursor.getAndSet(value)),
			new Writer("getAndUpdate",(cursor,value) -> cursor.getAndUpdate(current -> value)),
			new Writer("updateAndGet",(cursor,value) -> cursor.updateAndGet(current -> value)),
			new Writer("getAndAccumulate",(cursor,value) -> cursor.getAndAccumulate(value,(current,x) -> x)),
			new Writer("accumulateAndGet",(cursor,value) -> cursor.accumulateAndGet(value,(current,x) -> x)),
			new Writer("compareAndSet",(cursor,value) -> assertTrue(cursor.compareAndSet(cursor.get(),value))),
			new Writer("transaction",(cursor,value) -> Transactions.execute(cursor,draft -> draft.set(value))),
			new Writer("exclusive transaction",(cursor,value) -> Transactions.executeExclusively(cursor,draft -> draft.set(value))));
	}

	static Stream<Arguments> storageAndWriters() {
		return storage().flatMap(storage -> writers().map(writer -> Arguments.of(storage,writer)));
	}

	// ===== Deciding by value =====

	@ParameterizedTest(name="{0}")
	@MethodSource("storage")
	void compareAndSetAcceptsAnEqualValueHeldAsAnotherObject(Storage storage) {
		ABlob held=blob(1);
		ABlob expected=blob(1);
		ABlob next=blob(2);
		var cursor=storage.holding.apply(held);
		assertNotSame(held,expected,"The test needs two objects with one value");

		assertTrue(cursor.compareAndSet(expected,next));
		assertSame(next,cursor.get());
	}

	@ParameterizedTest(name="{0}")
	@MethodSource("storage")
	void compareAndSetRefusesADifferentValue(Storage storage) {
		ABlob held=blob(1);
		var cursor=storage.holding.apply(held);

		assertFalse(cursor.compareAndSet(blob(3),blob(2)));
		assertSame(held,cursor.get());
	}

	// ===== Which object is held =====

	@ParameterizedTest(name="{0}, {1}")
	@MethodSource("storageAndWriters")
	void aChangedWriteHoldsTheObjectWritten(Storage storage,Writer writer) {
		ABlob next=blob(2);
		var cursor=storage.holding.apply(blob(1));

		writer.write.accept(cursor,next);

		assertSame(next,cursor.get());
	}

	@ParameterizedTest(name="{0}, {1}")
	@MethodSource("storageAndWriters")
	void aWriteOfAnEqualValueKeepsTheObjectHeld(Storage storage,Writer writer) {
		ABlob held=blob(1);
		var cursor=storage.holding.apply(held);

		writer.write.accept(cursor,blob(1));

		assertSame(held,cursor.get());
	}

	@Test void anEditAtOnePathKeepsEveryOtherEntryAsTheObjectItWas() {
		ABlob untouched=blob(1);
		ABlob next=blob(3);
		Root<AHashMap<Keyword,ACell>> root=Root.create(Maps.of(KEY,blob(2),OTHER,untouched));
		var before=root.get();

		root.path(KEY).set(next);

		assertNotSame(before,root.get(),"A changed value is a new object");
		assertSame(next,root.get().get(KEY));
		assertSame(untouched,root.get().get(OTHER));
	}

	@Test void aTransactionEditingOnePathKeepsEveryOtherEntryAsTheObjectItWas() {
		ABlob untouched=blob(1);
		ABlob next=blob(3);
		Root<AHashMap<Keyword,ACell>> root=Root.create(Maps.of(KEY,blob(2),OTHER,untouched));

		Transactions.execute(root,draft -> draft.path(KEY).set(next));

		assertSame(next,root.get().get(KEY));
		assertSame(untouched,root.get().get(OTHER));
	}
}
