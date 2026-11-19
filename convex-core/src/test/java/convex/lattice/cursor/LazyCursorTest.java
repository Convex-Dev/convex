package convex.lattice.cursor;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.UnaryOperator;

import org.junit.jupiter.api.Test;

import convex.core.data.ACell;
import convex.core.data.AHashMap;
import convex.core.data.Index;
import convex.core.data.Keyword;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.prim.CVMLong;

/** Lazy cursors: defaults while a location does not exist, construction on a changed write, refusal where construction fails. */
class LazyCursorTest {
	static final Keyword A=Keyword.intern("a");
	static final Keyword B=Keyword.intern("b");
	static final CVMLong DEFAULT=CVMLong.create(7);

	@Test void withoutDefaultAMissingLocationIsAbsentAndOnlyAChangedWriteConstructsIt() {
		var root=Root.<ACell>create(Maps.empty());
		LazyCursor<ACell> lazy=LazyCursor.create(root,A);
		assertFalse(lazy.exists());
		assertNull(lazy.getOrDefault(null));
		assertThrows(MissingValueException.class,lazy::get);
		lazy.set(null);
		assertEquals(Maps.empty(),root.get(),"writing nil to a missing location changes nothing");
		lazy.set(CVMLong.ONE);
		assertEquals(Maps.of(A,CVMLong.ONE),root.get());
	}

	@Test void aMissingLocationReadsAsItsDefaultEvenWhereTheSourceIsAbsent() {
		var root=new Root<ACell>();
		LazyCursor<CVMLong> lazy=LazyCursor.create(root.withDefault(Maps.empty()),A,DEFAULT);
		assertTrue(lazy.exists());
		assertEquals(DEFAULT,lazy.get());
		LazyCursor<CVMLong> overAbsent=LazyCursor.create(root,A,DEFAULT);
		assertEquals(DEFAULT,overAbsent.get(),"the default is read without a container");
		lazy.set(DEFAULT);
		assertNull(root.get(),"writing the default constructs nothing");
		lazy.updateAndGet(v -> CVMLong.create(v.longValue()+1));
		assertEquals(Maps.of(A,CVMLong.create(8)),root.get());
	}

	@Test void aStoredNilIsPresentNotTheDefault() {
		var root=Root.<ACell>create(Maps.of(A,null));
		LazyCursor<CVMLong> lazy=LazyCursor.create(root,A,DEFAULT);
		assertTrue(lazy.exists());
		assertNull(lazy.get());
		lazy.set(DEFAULT);
		assertEquals(Maps.of(A,DEFAULT),root.get());
		lazy.set(null);
		assertEquals(Maps.of(A,null),root.get(),"writing nil over a default reads back nil");
		assertNull(lazy.get());
	}

	@Test void aChainWithADefaultAtEachLevelConstructsThePathInOneWrite() {
		var updates=new AtomicInteger();
		var root=new Root<ACell>() {
			@Override public ACell getAndUpdate(UnaryOperator<ACell> update) {
				updates.incrementAndGet();
				return super.getAndUpdate(update);
			}
		};
		LazyCursor<AHashMap<Keyword,ACell>> record=LazyCursor.create(root.withDefault(Index.none()),A,Maps.empty());
		LazyCursor<ACell> field=LazyCursor.create(record,B);
		field.set(null);
		assertNull(root.get(),"an unchanged write constructs no level");
		assertEquals(1,updates.get(),"an unchanged write is still one source update");
		field.set(CVMLong.ONE);
		assertEquals(Index.of(A,Maps.of(B,CVMLong.ONE)),root.get());
		assertEquals(2,updates.get(),"every level is constructed in one source update");
	}

	@Test void everyWriteIsRefusedWhereALevelCannotBeConstructed() {
		var root=new Root<ACell>();
		LazyCursor<AHashMap<Keyword,ACell>> record=LazyCursor.create(root,A,Maps.empty());
		LazyCursor<ACell> field=LazyCursor.create(record,B);
		assertNull(field.getOrDefault(null),"reads work without a container");
		assertThrows(IllegalStateException.class,() -> field.set(null));
		assertThrows(IllegalStateException.class,() -> field.set(CVMLong.ONE));
		assertNull(root.get());

		var fork=field.fork();
		assertThrows(IllegalStateException.class,() -> fork.reconcile((base,own,other) -> own),"an unchanged fork is refused at sync");
		fork.set(CVMLong.TWO);
		assertThrows(IllegalStateException.class,() -> fork.reconcile((base,own,other) -> own));
		assertEquals(CVMLong.TWO,fork.get(),"a refused sync keeps the working value");
		root.set(Index.none());
		assertEquals(CVMLong.TWO,fork.reconcile((base,own,other) -> own));
		assertEquals(Index.of(A,Maps.of(B,CVMLong.TWO)),root.get());
	}

	@Test void aStoredValueOfAnotherKindThanADataStructureDefaultIsRejected() {
		var root=Root.<ACell>create(Maps.of(A,Maps.of(B,CVMLong.ONE)));
		LazyCursor<Index<Keyword,ACell>> indexed=LazyCursor.create(root,A,Index.none());
		assertThrows(IllegalStateException.class,indexed::get);
		assertThrows(IllegalStateException.class,() -> indexed.set(Index.none()));
		assertEquals(Maps.of(A,Maps.of(B,CVMLong.ONE)),root.get());

		var scalar=Root.<ACell>create(Strings.intern("not a container"));
		LazyCursor<ACell> inside=LazyCursor.create(scalar,A);
		assertThrows(IllegalStateException.class,inside::exists);
		assertThrows(IllegalStateException.class,() -> inside.set(CVMLong.ONE));
		assertEquals(Strings.intern("not a container"),scalar.get());
	}

	@Test void aKeyTheContainerCannotHoldIsRefusedForEveryWrite() {
		var root=Root.<ACell>create(Index.none());
		LazyCursor<ACell> lazy=LazyCursor.create(root,CVMLong.ONE);
		assertFalse(lazy.exists());
		assertThrows(IllegalArgumentException.class,() -> lazy.set(null));
		assertThrows(IllegalArgumentException.class,() -> lazy.set(CVMLong.ONE));
		assertEquals(Index.none(),root.get());
	}

	@Test void readsNeverConstruct() {
		var constructions=new AtomicInteger();
		var root=new Root<ACell>();
		var lazy=new ALazyCursor<CVMLong>(root,DEFAULT) {
			@Override protected Object locate(ACell stored) { return stored==null?MISSING:stored; }
			@Override protected ACell construct(ACell stored,CVMLong value) {
				constructions.incrementAndGet();
				return value;
			}
		};
		assertEquals(DEFAULT,lazy.get());
		assertTrue(lazy.exists());
		assertEquals(DEFAULT,lazy.fork().get());
		assertEquals(0,constructions.get());
		lazy.set(CVMLong.ONE);
		assertEquals(CVMLong.ONE,root.get());
	}
}
