package convex.lattice;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import convex.core.cvm.Address;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Blob;
import convex.core.data.Index;
import convex.core.data.Keyword;
import convex.core.data.Lists;
import convex.core.data.Maps;
import convex.core.data.Path;
import convex.core.data.Sets;
import convex.core.data.Strings;
import convex.core.data.Symbol;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import convex.lattice.cursor.AForkableCursor;
import convex.lattice.cursor.MissingValueException;
import convex.lattice.cursor.ProjectionCursor;
import convex.lattice.cursor.Root;
import convex.lattice.cursor.Transactions;

class ValueComponentTest {
	private static final AString DATA=Strings.create("data");
	private static final AString NAME=Strings.create("name");
	private static final AString ZERO=Strings.create("0");
	private static final AString ONE=Strings.create("1");
	private static final AString JSON=Strings.create("json");
	private static final AString RAW=Strings.create("raw");

	/** The parent chooses an interpretation for the same underlying value. */
	private static class Parent extends ALatticeComponent<ACell> {
		Parent(AForkableCursor<ACell> cursor) { super(cursor); }

		@Override public ALatticeComponent<?> resolve(Path path) {
			if (path.isEmpty()) return this;
			if (JSON.equals(path.get(0))) return new JSONValueComponent<>(cursor().path(DATA)).resolve(path.slice(1));
			if (RAW.equals(path.get(0))) return new ValueComponent<>(cursor().path(DATA)).resolve(path.slice(1));
			return super.resolve(path);
		}
	}

	@Test void baseHasValuesButNoImplicitNamespaceOrMerge() {
		var root=Root.<ACell>create(null);
		var base=new ALatticeComponent<>(root) {};
		assertNull(base.value());
		assertTrue(base.exists());
		assertSame(base,base.resolve());
		assertSame(base,base.resolve(Path.of(NAME).slice(1)));
		var none=assertThrows(NoSuchPathException.class,() -> base.resolve(NAME));
		assertEquals(0,none.getIndex());
		assertEquals(NAME,none.getSegment());
		assertThrows(NullPointerException.class,() -> base.resolve(NAME,null));
		assertThrows(UnsupportedOperationException.class,() -> new ValueComponent<>(root).merge(CVMLong.ONE));
		assertThrows(UnsupportedOperationException.class,() -> new JSONValueComponent<>(root).merge(CVMLong.ONE));
	}

	@Test void literalResolutionOwnsItsSegmentsAndDoesNotRead() {
		AtomicInteger reads=new AtomicInteger();
		Root<ACell> root=new Root<>(Maps.of(DATA,Maps.of(NAME,CVMLong.ONE))) {
			@Override public ACell get() { reads.incrementAndGet(); return super.get(); }
		};
		var component=new ValueComponent<>(root);
		AString[] segments={DATA,NAME};
		var child=component.resolve(segments);
		assertEquals(0,reads.get());
		segments[0]=NAME;
		segments[1]=DATA;
		assertEquals(CVMLong.ONE,child.value());
		child.cursor().set(CVMLong.TWO);
		assertEquals(CVMLong.TWO,RT.getIn(root.get(),DATA,NAME));
		assertSame(component,component.resolve(Path.of(DATA).slice(1)));
	}

	@Test void parentSelectsJSONAndDelegatesSlicesInsideTransactions() {
		var root=Root.<ACell>create(Maps.of(DATA,Vectors.of(CVMLong.ONE)));
		var parent=new Parent(root);
		Path path=Path.of(NAME,JSON,ZERO).slice(1);
		assertEquals(CVMLong.ONE,parent.resolve(path).value());
		assertFalse(parent.resolve(RAW,ZERO).exists());
		assertThrows(MissingValueException.class,() -> parent.resolve(RAW,ZERO).value());
		Transactions.execute(root,draft -> {
			var resolved=new Parent(draft).resolve(path);
			resolved.cursor().set(CVMLong.TWO);
			assertEquals(CVMLong.ONE,parent.resolve(path).value());
			assertEquals(CVMLong.TWO,resolved.value());
		});
		assertEquals(CVMLong.TWO,parent.resolve(path).value());
	}

	@Test void unknownSegmentReportsItsPositionInTheRequest() {
		var parent=new Parent(Root.<ACell>create(null));
		Path request=Path.of(JSON,NAME,DATA);
		var none=assertThrows(NoSuchPathException.class,() -> parent.resolve(request.slice(1)));
		assertEquals(1,none.getIndex());
		assertEquals(NAME,none.getSegment());
		assertEquals(Path.of(NAME,DATA),none.getPath());
		assertEquals(request,none.getPath().request());
		assertThrows(IllegalArgumentException.class,() -> new NoSuchPathException(request.slice(3)));
		// Every segment of a value component names a place, which may be absent
		assertFalse(parent.resolve(JSON,NAME,DATA).exists());
	}

	@Test void exposedCursorViewIsUsedForValueAndDescendants() {
		var root=Root.<ACell>create(Maps.of(DATA,Maps.of(NAME,CVMLong.ONE),RAW,CVMLong.ZERO));
		var component=new ValueComponent<ACell>(root) {
			private final AForkableCursor<ACell> exposed=ProjectionCursor.create(root,
				stored -> RT.getIn(stored,DATA),(stored,value) -> LatticeOps.assocExisting(stored,value,DATA));
			@Override public AForkableCursor<ACell> cursor() { return exposed; }
		};
		assertEquals(Maps.of(NAME,CVMLong.ONE),component.value());
		component.resolve(NAME).cursor().set(CVMLong.TWO);
		assertEquals(Maps.of(DATA,Maps.of(NAME,CVMLong.TWO),RAW,CVMLong.ZERO),root.get());
	}

	@Test void JSONUsesOneSnapshotAndPreservesSegmentOwnership() {
		AtomicInteger reads=new AtomicInteger();
		Root<ACell> root=new Root<>(Maps.of(DATA,Vectors.of(Maps.of(NAME,CVMLong.ONE)))) {
			@Override public ACell get() { reads.incrementAndGet(); return super.get(); }
		};
		var json=new JSONValueComponent<>(root);
		assertSame(json,json.resolve());
		assertEquals(0,reads.get());
		AString[] segments={DATA,ZERO,NAME};
		var child=json.resolve(segments);
		assertEquals(1,reads.get());
		segments[1]=NAME;
		assertEquals(CVMLong.ONE,child.value());
		assertInstanceOf(JSONValueComponent.class,json.resolve(DATA));
		assertEquals(child.value(),json.resolve(DATA).resolve(ZERO).resolve(NAME).value());
		assertThrows(NullPointerException.class,() -> json.resolve(DATA,null));
	}

	@Test void JSONObjectKeysRemainNamesAndPresentStringsWinOverKeywords() {
		Keyword keyword=Keyword.create(NAME);
		var root=Root.<ACell>create(Maps.of(NAME,null,keyword,CVMLong.ONE,ZERO,CVMLong.TWO));
		var json=new JSONValueComponent<>(root);
		assertTrue(json.resolve(NAME).exists());
		assertNull(json.resolve(NAME).value());
		assertEquals(CVMLong.TWO,json.resolve(ZERO).value());
		root.set(Maps.of(keyword,null));
		assertTrue(json.resolve(NAME).exists());
		assertNull(json.resolve(NAME).value());
		assertFalse(new ValueComponent<>(root).resolve(NAME).exists());
		root.set(Maps.of(keyword,CVMLong.ONE));
		var chosen=json.resolve(NAME);
		root.set(Maps.of(keyword,CVMLong.ONE,NAME,CVMLong.TWO));
		assertEquals(CVMLong.ONE,chosen.value());
		assertEquals(CVMLong.TWO,json.resolve(NAME).value());
	}

	@Test void JSONPositionsAreCanonicalDecimalsInVectorsAndLists() {
		var json=new JSONValueComponent<>(Root.<ACell>create(Vectors.of(null,CVMLong.ONE)));
		assertTrue(json.resolve(ZERO).exists());
		assertNull(json.resolve(ZERO).value());
		assertEquals(CVMLong.ONE,json.resolve(ONE).value());
		for (String segment:new String[] {"", "01", "-1", "+1", "-0", " 1", "1.0", "0x01", "2", "9223372036854775807", "9223372036854775808", "١"}) {
			var child=json.resolve(Strings.create(segment));
			assertFalse(child.exists(),segment);
			assertThrows(MissingValueException.class,child::value,segment);
		}
		var list=Root.<ACell>create(Lists.of(CVMLong.ONE,CVMLong.TWO));
		var element=new JSONValueComponent<>(list).resolve(ONE);
		assertEquals(CVMLong.TWO,element.value());
		element.cursor().set(CVMLong.ZERO);
		assertEquals(Lists.of(CVMLong.ONE,CVMLong.ZERO),list.get());
		assertFalse(new JSONValueComponent<>(Root.create(Sets.of(CVMLong.ONE))).resolve(ZERO).exists());
	}

	@Test void JSONMapSegmentsNameKeysAsTheirJSONRenderingShows() {
		Blob blob=Blob.fromHex("abcd");
		Address address=Address.create(12);
		CVMLong negative=CVMLong.create(-7);
		var root=Root.<ACell>create(Maps.of(Symbol.create(NAME),CVMLong.ONE,blob,CVMLong.TWO,address,CVMLong.ZERO,negative,null));
		var json=new JSONValueComponent<>(root);
		assertEquals(CVMLong.ONE,json.resolve(NAME).value());
		assertEquals(CVMLong.TWO,json.resolve(Strings.create("0xabcd")).value());
		assertEquals(CVMLong.TWO,json.resolve(Strings.create("abcd")).value());
		assertEquals(CVMLong.ZERO,json.resolve(Strings.create("#12")).value());
		assertEquals(CVMLong.ZERO,json.resolve(Strings.create("12")).value());
		assertTrue(json.resolve(Strings.create("-7")).exists());
		assertNull(json.resolve(Strings.create("-7")).value());
		json.resolve(Strings.create("0xabcd")).cursor().set(CVMLong.ONE);
		assertEquals(CVMLong.ONE,RT.getIn(root.get(),blob));
		var blobs=new JSONValueComponent<>(Root.create(Index.of(blob,CVMLong.ONE)));
		assertEquals(CVMLong.ONE,blobs.resolve(Strings.create("abcd")).value());
		assertEquals(CVMLong.ONE,blobs.resolve(Strings.create("0xabcd")).value());
	}

	@Test void JSONMapSegmentsPreferStringsThenNamesThenParsedKeys() {
		AString twelve=Strings.create("12");
		ACell[] keys={twelve,Keyword.create(twelve),Symbol.create(twelve),Blob.fromHex("12"),Address.create(12),CVMLong.create(12)};
		AMap<ACell,ACell> map=Maps.empty();
		for (int i=0; i<keys.length; i++) map=map.assoc(keys[i],CVMLong.create(i));
		var root=Root.<ACell>create(map);
		var json=new JSONValueComponent<>(root);
		for (int i=0; i<keys.length; i++) {
			assertEquals(CVMLong.create(i),json.resolve(twelve).value());
			map=map.dissoc(keys[i]);
			root.set(map);
		}
		assertFalse(json.resolve(twelve).exists());
	}

	@Test void JSONIntegerMapKeysNeverBecomePositions() {
		var root=Root.<ACell>create(Maps.of(CVMLong.ZERO,CVMLong.ONE));
		var key=new JSONValueComponent<>(root).resolve(ZERO);
		assertEquals(CVMLong.ONE,key.value());
		root.set(Vectors.of(CVMLong.TWO));
		assertFalse(key.exists());
		assertEquals(0,assertThrows(NoSuchPathException.class,() -> key.cursor().set(CVMLong.ONE)).getIndex());
		assertEquals(Vectors.of(CVMLong.TWO),root.get());
	}

	@Test void JSONKeysStayBoundAndWritesCannotChangeContainerKinds() {
		var root=Root.<ACell>create(Maps.of(DATA,Vectors.of(CVMLong.ONE)));
		var json=new JSONValueComponent<>(root);
		var old=json.resolve(DATA,ZERO);
		ACell changed=Maps.of(DATA,Maps.of(ZERO,CVMLong.TWO,CVMLong.ZERO,CVMLong.ONE));
		root.set(changed);
		assertFalse(old.exists());
		assertEquals(1,assertThrows(NoSuchPathException.class,() -> old.cursor().set(CVMLong.TWO)).getIndex());
		assertSame(changed,root.get());
		assertEquals(CVMLong.TWO,json.resolve(DATA,ZERO).value());
		root.set(Maps.empty());
		var absent=json.resolve(DATA,NAME);
		assertFalse(absent.exists());
		assertEquals(1,assertThrows(NoSuchPathException.class,() -> absent.cursor().set(CVMLong.ONE)).getIndex());
		assertEquals(Maps.empty(),root.get());
		json.resolve(NAME).cursor().set(CVMLong.ONE);
		assertEquals(Maps.of(NAME,CVMLong.ONE),root.get());
	}

	@Test void refusedWritesNameTheSegmentInTheRequest() {
		AString two=Strings.create("2");
		var root=Root.<ACell>create(Maps.of(DATA,Vectors.of(CVMLong.ONE),NAME,CVMLong.ONE));
		var json=new JSONValueComponent<>(root);
		// A position past the end, appending included, cannot be held
		Path request=Path.of(JSON,DATA,ONE);
		var past=assertThrows(NoSuchPathException.class,() -> json.resolve(request.slice(1)).cursor().set(CVMLong.TWO));
		assertEquals(2,past.getIndex());
		assertEquals(ONE,past.getSegment());
		assertEquals(Path.of(ONE),past.getPath());
		assertEquals(request,past.getPath().request());
		// A key below a scalar cannot be held
		assertEquals(1,assertThrows(NoSuchPathException.class,() -> json.resolve(NAME,ZERO).cursor().set(CVMLong.TWO)).getIndex());
		// An absent level: the container is the path before the segment
		var missing=assertThrows(NoSuchPathException.class,() -> json.resolve(RAW,ZERO,two).cursor().set(CVMLong.TWO));
		assertEquals(1,missing.getIndex());
		var none=assertThrows(NoSuchPathException.class,() -> new JSONValueComponent<>(Root.<ACell>create(null)).resolve(DATA).cursor().set(CVMLong.TWO));
		assertEquals(0,none.getIndex());
		// Literal paths refuse the same way
		var literal=new ValueComponent<>(root);
		assertEquals(1,assertThrows(NoSuchPathException.class,() -> literal.resolve(NAME,ZERO).cursor().set(CVMLong.TWO)).getIndex());
		assertEquals(1,assertThrows(NoSuchPathException.class,() -> literal.resolve(RAW,ZERO).cursor().set(CVMLong.TWO)).getIndex());
		assertInstanceOf(IllegalStateException.class,missing);
		assertEquals(Maps.of(DATA,Vectors.of(CVMLong.ONE),NAME,CVMLong.ONE),root.get());
		// Unchanged writes touch nothing, so they are not refused
		json.resolve(RAW,ZERO).cursor().set(null);
	}

	@Test void JSONForkRetainsItsResolutionPolicyAndPublicationBoundary() {
		var root=Root.<ACell>create(Maps.of(DATA,Vectors.of(CVMLong.ONE)));
		var live=new JSONValueComponent<>(root);
		var draft=live.fork();
		draft.resolve(DATA,ZERO).cursor().set(CVMLong.TWO);
		assertEquals(CVMLong.ONE,live.resolve(DATA,ZERO).value());
		assertEquals(CVMLong.TWO,draft.resolve(DATA,ZERO).value());
		draft.sync();
		assertEquals(CVMLong.TWO,live.resolve(DATA,ZERO).value());
		var conflicting=live.fork();
		conflicting.cursor().set(CVMLong.ONE);
		live.cursor().set(CVMLong.TWO);
		assertThrows(UnsupportedOperationException.class,conflicting::sync);
	}
}
